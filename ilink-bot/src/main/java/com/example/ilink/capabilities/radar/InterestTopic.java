package com.example.ilink.capabilities.radar;

import java.time.LocalDateTime;
import java.util.List;

/** 用户明确订阅的信息主题。 */
public record InterestTopic(
        String id,
        String name,
        List<String> includeTerms,
        List<String> excludeTerms,
        boolean enabled,
        LocalDateTime createdAt,
        RadarTopicOrigin origin,
        RadarTopicPriority priority,
        double confidence,
        LocalDateTime updatedAt,
        LocalDateTime expiresAt) {

    public InterestTopic {
        id = id == null ? "" : id.trim();
        name = name == null ? "" : name.trim();
        includeTerms = includeTerms == null ? List.of() : List.copyOf(includeTerms);
        excludeTerms = excludeTerms == null ? List.of() : List.copyOf(excludeTerms);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        origin = origin == null ? RadarTopicOrigin.EXPLICIT_USER : origin;
        priority = priority == null ? RadarTopicPriority.NORMAL : priority;
        confidence = Math.max(0, Math.min(1, confidence));
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public InterestTopic(String id, String name, List<String> includeTerms,
                         List<String> excludeTerms, boolean enabled, LocalDateTime createdAt) {
        this(id, name, includeTerms, excludeTerms, enabled, createdAt,
                RadarTopicOrigin.EXPLICIT_USER, RadarTopicPriority.HIGH, 1.0, createdAt, null);
    }

    public InterestTopic withEnabled(boolean value) {
        return new InterestTopic(id, name, includeTerms, excludeTerms, value, createdAt,
                origin, priority, confidence, LocalDateTime.now(), expiresAt);
    }
}
