package com.example.ilink.capabilities.documents.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;

public final class VectorStore {

    private final Map<String, List<ChunkWithVector>> userVectors = new ConcurrentHashMap<>();

    public void store(String userId, TextChunk chunk, List<Float> vector) {
        userVectors.computeIfAbsent(userId, k -> new ArrayList<>())
                .add(new ChunkWithVector(chunk, vector));
    }

    public void clear(String userId) {
        userVectors.remove(userId);
    }

    public List<ScoredChunk> search(String userId, List<Float> queryVector, int topK) {
        List<ChunkWithVector> chunks = userVectors.get(userId);
        if (chunks == null || chunks.isEmpty() || topK <= 0) return List.of();

        PriorityQueue<ScoredChunk> topResults = new PriorityQueue<>(
                topK, (a, b) -> Double.compare(a.score, b.score));
        for (ChunkWithVector cwv : chunks) {
            double score = cosineSimilarity(queryVector, cwv.vector);
            ScoredChunk candidate = new ScoredChunk(cwv.chunk, score);
            if (topResults.size() < topK) {
                topResults.offer(candidate);
            } else if (score > topResults.peek().score) {
                topResults.poll();
                topResults.offer(candidate);
            }
        }

        List<ScoredChunk> scored = new ArrayList<>(topResults);
        scored.sort((a, b) -> Double.compare(b.score, a.score));
        return scored;
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

    private record ChunkWithVector(TextChunk chunk, List<Float> vector) {}

    public record ScoredChunk(TextChunk chunk, double score) {}
}
