package com.example.ilink.capabilities.documents.rag;

import com.example.ilink.platform.persistence.MySqlStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 按用户隔离的向量存储；MySQL 可用时自动持久化，不可用时退化为内存。 */
public final class VectorStore {

    private final Map<String, List<ChunkWithVector>> userVectors = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userDocumentHashes = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database;

    public VectorStore() {
        this(true);
    }

    public VectorStore(boolean persistent) {
        this.database = persistent ? MySqlStore.getInstance() : null;
    }

    /** 兼容单片段写入；正式文档索引使用 storeDocument 保证一次性落库。 */
    public void store(String userId, TextChunk chunk, List<Float> vector) {
        ensureLoaded(userId);
        vectors(userId).add(new ChunkWithVector(chunk, List.copyOf(vector)));
    }

    public boolean hasDocument(String userId, String contentHash) {
        ensureLoaded(userId);
        return userDocumentHashes.getOrDefault(userId, Set.of()).contains(contentHash)
                || database != null && database.hasKnowledgeDocument(userId, contentHash);
    }

    public boolean hasKnowledge(String userId) {
        ensureLoaded(userId);
        return !vectors(userId).isEmpty();
    }

    public void storeDocument(String userId, String documentId, String contentHash,
                              List<EmbeddedChunk> chunks) {
        ensureLoaded(userId);
        List<MySqlStore.KnowledgeChunkRow> rows = new ArrayList<>();
        for (EmbeddedChunk embedded : chunks) {
            TextChunk chunk = embedded.chunk();
            vectors(userId).add(new ChunkWithVector(chunk, List.copyOf(embedded.vector())));
            rows.add(new MySqlStore.KnowledgeChunkRow(chunk.id(), documentId, contentHash,
                    chunk.fileName(), chunk.chunkIndex(), chunk.text(), embedded.vector()));
        }
        userDocumentHashes.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet())
                .add(contentHash);
        String fileName = chunks.isEmpty() ? "" : chunks.getFirst().chunk().fileName();
        if (database != null) {
            database.saveKnowledgeDocument(userId, documentId, fileName, contentHash, rows);
        }
    }

    public void clear(String userId) {
        userVectors.remove(userId);
        userDocumentHashes.remove(userId);
        loadedUsers.remove(userId);
    }

    public List<ScoredChunk> search(String userId, List<Float> queryVector, int topK) {
        return search(userId, queryVector, topK, 0);
    }

    public List<ScoredChunk> search(String userId, List<Float> queryVector, int topK, double minScore) {
        ensureLoaded(userId);
        List<ChunkWithVector> chunks = vectors(userId);
        if (chunks.isEmpty() || topK <= 0) return List.of();

        PriorityQueue<ScoredChunk> topResults = new PriorityQueue<>(
                topK, (a, b) -> Double.compare(a.score, b.score));
        synchronized (chunks) {
            for (ChunkWithVector cwv : chunks) {
                if (queryVector.size() != cwv.vector.size()) continue;
                double score = cosineSimilarity(queryVector, cwv.vector);
                if (score < minScore) continue;
                ScoredChunk candidate = new ScoredChunk(cwv.chunk, score);
                if (topResults.size() < topK) {
                    topResults.offer(candidate);
                } else if (score > topResults.peek().score) {
                    topResults.poll();
                    topResults.offer(candidate);
                }
            }
        }

        List<ScoredChunk> scored = new ArrayList<>(topResults);
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
    }

    private void ensureLoaded(String userId) {
        if (!loadedUsers.add(userId)) return;
        if (database == null) return;
        List<MySqlStore.KnowledgeChunkRow> rows = database.loadKnowledgeChunks(userId);
        if (rows.isEmpty()) return;
        List<ChunkWithVector> chunks = vectors(userId);
        Set<String> hashes = userDocumentHashes.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet());
        for (MySqlStore.KnowledgeChunkRow row : rows) {
            chunks.add(new ChunkWithVector(new TextChunk(row.id(), row.fileName(), row.chunkIndex(),
                    row.content(), preview(row.content())), List.copyOf(row.embedding())));
            hashes.add(row.contentHash());
        }
    }

    private List<ChunkWithVector> vectors(String userId) {
        return userVectors.computeIfAbsent(userId,
                ignored -> Collections.synchronizedList(new ArrayList<>()));
    }

    private static String preview(String text) {
        return text.length() > 20 ? text.substring(0, 20) + "..." : text;
    }

    private double cosineSimilarity(List<Float> a, List<Float> b) {
        if (a.size() != b.size()) return 0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.size(); i++) {
            dot += a.get(i) * b.get(i);
            normA += a.get(i) * a.get(i);
            normB += b.get(i) * b.get(i);
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0 ? 0 : dot / denom;
    }

    private record ChunkWithVector(TextChunk chunk, List<Float> vector) { }

    public record EmbeddedChunk(TextChunk chunk, List<Float> vector) { }

    public record ScoredChunk(TextChunk chunk, double score) { }
}
