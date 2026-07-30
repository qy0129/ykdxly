package com.example.ilink.capabilities.memory;

/** 经过规则或模型判定后，允许写入长期记忆的候选项。 */
public record MemoryCandidate(String userId, String type, String key, String content,
                              int importance, String source) { }
