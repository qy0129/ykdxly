package com.example.ilink.feature.media;

/** 媒体资料查询结果以及后续哔哩哔哩搜索参数。 */
public record MediaKnowledgeResponse(
        String text,
        String bilibiliQuery,
        String bilibiliCategory) {
}
