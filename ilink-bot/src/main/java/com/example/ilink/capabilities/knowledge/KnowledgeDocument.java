package com.example.ilink.capabilities.knowledge;

import java.time.LocalDateTime;

public record KnowledgeDocument(
        String id,
        String userId,
        String fileName,
        String extension,
        long fileSize,
        int chunkCount,
        String contentHash,
        LocalDateTime createdAt) {
}
