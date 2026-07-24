package com.example.ilink.model;

/** 联网搜索或新闻查询返回的一条结构化结果。 */
public record SearchResult(
        String title,
        String summary,
        String source,
        String publishedAt,
        String url) {
}
