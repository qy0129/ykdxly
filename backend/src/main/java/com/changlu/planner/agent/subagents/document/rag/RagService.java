package com.changlu.planner.agent.subagents.document.rag;

import com.changlu.planner.agent.subagents.document.DocumentParserTool;
import com.changlu.planner.agent.subagents.document.DocumentResult;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.changlu.planner.shared.database.Database;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 负责编排文档分块、向量索引和相关片段检索。 */
public final class RagService {
  private static final Logger LOG = LoggerFactory.getLogger(RagService.class);
  private static final int MAX_CONTEXT_CHARS = 24000;

  private final DocumentChunker chunker = new DocumentChunker();
  private final EmbeddingClient embeddings = new EmbeddingClient();
  private final RagRepository repository;
  private final double minScore = Double.parseDouble(EnvironmentConfig.value(
      "PLANNER_RAG_MIN_SCORE", "rag.min.score", "0.5"));

  public RagService(Database database) { repository = new RagRepository(database); }

  public DocumentResult index(Database.Context context, DocumentParserTool.ParsedDocument document)
      throws Exception {
    String hash = sha256(document.text());
    Optional<DocumentResult> duplicate = repository.find(context, hash);
    if (duplicate.isPresent()) return duplicate.get();

    List<String> textChunks = chunker.chunk(document.text());
    List<RagRepository.StoredChunk> stored = new ArrayList<>(textChunks.size());
    boolean indexed = embeddings.configured();
    if (indexed) {
      try {
        for (String chunk : textChunks) stored.add(new RagRepository.StoredChunk(chunk, embeddings.embed(chunk)));
      } catch (Exception error) {
        indexed = false;
        stored.clear();
        LOG.warn("[文档索引降级] 文件={} 原因={}", document.fileName(), error.getMessage());
      }
    }
    if (!indexed) textChunks.forEach(chunk -> stored.add(new RagRepository.StoredChunk(chunk, null)));
    return repository.save(context, UUID.randomUUID(), document.fileName(), document.mediaType(),
        document.extension(), hash, document.text(), stored, indexed);
  }

  public RetrievedContext retrieve(Database.Context context, String query, List<UUID> documentIds)
      throws Exception {
    if (!documentIds.isEmpty()) {
      String prompt = repository.contextForDocuments(context, documentIds, MAX_CONTEXT_CHARS);
      return new RetrievedContext(prompt, sources(prompt));
    }
    if (query == null || query.isBlank()) return new RetrievedContext("", List.of());
    if (!embeddings.configured()) {
      String prompt = repository.latestContext(context, MAX_CONTEXT_CHARS);
      return new RetrievedContext(prompt, sources(prompt));
    }
    try {
      List<Float> queryVector = embeddings.embed(query);
      List<ScoredChunk> matches = repository.vectorChunks(context).stream()
          .filter(chunk -> chunk.embedding().size() == queryVector.size())
          .map(chunk -> new ScoredChunk(chunk, cosine(queryVector, chunk.embedding())))
          .filter(chunk -> chunk.score() >= minScore)
          .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed()).limit(5).toList();
      StringBuilder prompt = new StringBuilder();
      for (ScoredChunk match : matches) prompt.append("[来源：").append(match.chunk().fileName())
          .append("，片段 ").append(match.chunk().index() + 1).append("]\n")
          .append(match.chunk().content()).append("\n\n");
      return new RetrievedContext(prompt.toString().strip(), sources(prompt.toString()));
    } catch (Exception error) {
      LOG.warn("[文档检索降级] 原因={}", error.getMessage());
      String prompt = repository.latestContext(context, MAX_CONTEXT_CHARS);
      return new RetrievedContext(prompt, sources(prompt));
    }
  }

  public void delete(Database.Context context, UUID documentId) throws Exception {
    repository.delete(context, documentId);
  }

  private double cosine(List<Float> left, List<Float> right) {
    double dot = 0, leftNorm = 0, rightNorm = 0;
    for (int index = 0; index < left.size(); index++) {
      dot += left.get(index) * right.get(index);
      leftNorm += left.get(index) * left.get(index);
      rightNorm += right.get(index) * right.get(index);
    }
    double denominator = Math.sqrt(leftNorm) * Math.sqrt(rightNorm);
    return denominator == 0 ? 0 : dot / denominator;
  }

  private List<String> sources(String prompt) {
    Set<String> values = new LinkedHashSet<>();
    for (String line : prompt.split("\\R")) {
      if (!line.startsWith("[来源：")) continue;
      int end = line.indexOf('，');
      if (end > 4) values.add(line.substring(4, end));
    }
    return List.copyOf(values);
  }

  private String sha256(String text) throws Exception {
    byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  public record RetrievedContext(String prompt, List<String> sources) {
    public boolean isEmpty() { return prompt == null || prompt.isBlank(); }
  }

  private record ScoredChunk(RagRepository.VectorChunk chunk, double score) {}
}
