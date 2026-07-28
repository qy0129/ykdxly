package com.example.ilink.capabilities.documents.rag;

import java.util.List;

public final class Retriever {

    private final DocumentChunker chunker = new DocumentChunker();
    private final EmbeddingService embeddingService;
    private final VectorStore vectorStore;

    public Retriever(EmbeddingService embeddingService, VectorStore vectorStore) {
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    public void indexDocument(String userId, String fileName, String text) throws Exception {
        List<TextChunk> chunks = chunker.chunk(fileName, text);
        for (TextChunk chunk : chunks) {
            List<Float> vector = embeddingService.embed(chunk.text());
            vectorStore.store(userId, chunk, vector);
        }
    }

    public String buildContext(String userId, String query, int topK) {
        try {
            List<Float> queryVector = embeddingService.embed(query);
            List<VectorStore.ScoredChunk> results = vectorStore.search(userId, queryVector, topK);

            if (results.isEmpty()) return "";

            StringBuilder context = new StringBuilder();
            context.append("以下是与问题相关的文档片段：\n\n");
            for (VectorStore.ScoredChunk sc : results) {
                context.append("[来源：").append(sc.chunk().fileName())
                        .append("·第").append(sc.chunk().chunkIndex() + 1).append("段]\n")
                        .append(sc.chunk().text()).append("\n\n");
            }
            return context.toString();
        } catch (Exception e) {
            System.err.println("[RAG] 检索失败: " + e.getMessage());
            return "";
        }
    }
}
