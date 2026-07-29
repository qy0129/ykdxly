package com.example.ilink.capabilities.knowledge;

public record KnowledgeChunk(
        String id,
        String documentId,
        String userId,
        String fileName,
        int chunkIndex,
        String content) {
}
