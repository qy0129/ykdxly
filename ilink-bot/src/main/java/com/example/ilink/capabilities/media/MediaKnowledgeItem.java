package com.example.ilink.capabilities.media;

/** 外部影视音乐资料源返回的一条统一结果。 */
public record MediaKnowledgeItem(
        String title,
        String detail,
        String summary,
        String source,
        String bilibiliQuery) {
}
