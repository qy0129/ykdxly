package com.example.ilink.capabilities.memory;

public record MemoryCandidate(
        String id,
        String userId,
        String type,
        String key,
        String content,
        int importance,
        String source) {

    public MemoryCandidate(String userId, String type, String key, String content, int importance, String source) {
        this(null, userId, type, key, content, importance, source);
    }
}
