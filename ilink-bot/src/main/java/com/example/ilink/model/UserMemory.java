package com.example.ilink.model;

import java.time.LocalDateTime;

/** 用户明确授权保存的一条结构化长期记忆。 */
public record UserMemory(
        String id,
        String userId,
        String type,
        String key,
        String value,
        String source,
        double confidence,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime lastUsedAt) {
}
