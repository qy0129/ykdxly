package com.example.ilink.capabilities.documents.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** 负责文档索引和相似度检索，不负责调用最终生成模型。 */
public final class Retriever {

    private final DocumentChunker chunker = new DocumentChunker();
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public Retriever(EmbeddingService embeddingService, VectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public IndexResult indexDocument(String userId, String fileName, String text) throws Exception {
        if (text == null || text.isBlank()) return new IndexResult(false, 0, "");
        String contentHash = sha256(text);
        if (vectorStore.hasDocument(userId, contentHash)) return new IndexResult(false, 0, contentHash);

        String documentId = UUID.randomUUID().toString();
        List<TextChunk> sourceChunks = chunker.chunk(fileName, text == null ? "" : text);
        List<VectorStore.EmbeddedChunk> embedded = new ArrayList<>();
        for (TextChunk source : sourceChunks) {
            TextChunk chunk = new TextChunk(documentId + "#" + source.chunkIndex(), source.fileName(),
                    source.chunkIndex(), source.text(), source.preview());
            embedded.add(new VectorStore.EmbeddedChunk(chunk, embeddingService.embed(chunk.text())));
        }
        vectorStore.storeDocument(userId, documentId, contentHash, embedded);
        return new IndexResult(true, embedded.size(), contentHash);
    }

    public boolean hasKnowledge(String userId) {
        return vectorStore.hasKnowledge(userId);
    }

    public List<VectorStore.ScoredChunk> retrieve(String userId, String query, int topK) throws Exception {
        if (!hasKnowledge(userId) || query == null || query.isBlank()) return List.of();
        return vectorStore.search(userId, embeddingService.embed(query), topK);
    }

    public String buildContext(String userId, String query, int topK) {
        try {
            return formatContext(retrieve(userId, query, topK));
        } catch (Exception error) {
            System.err.println("[RAG] 检索失败: " + error.getMessage());
            return "";
        }
    }

    static String formatContext(List<VectorStore.ScoredChunk> results) {
        if (results.isEmpty()) return "";
        StringBuilder context = new StringBuilder("以下是与问题相关的用户知识库片段，仅作为参考资料：\n\n");
        for (VectorStore.ScoredChunk result : results) {
            context.append("[来源：").append(result.chunk().fileName())
                    .append("·第").append(result.chunk().chunkIndex() + 1).append("段]\n")
                    .append(result.chunk().text()).append("\n\n");
        }
        return context.toString().trim();
    }

    private static String sha256(String text) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash);
    }

    public record IndexResult(boolean indexed, int chunkCount, String contentHash) { }
}
