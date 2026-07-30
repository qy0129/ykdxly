package com.example.ilink.capabilities.radar;

import java.time.LocalDateTime;

/** 新闻、网页和视频进入摘要前使用的统一候选结构。 */
public record RadarContentItem(
        String topic,
        RadarContentType type,
        String title,
        String summary,
        String source,
        String publishedAt,
        String url,
        String eventKey,
        int score,
        String rationale,
        boolean officialSignal,
        LocalDateTime discoveredAt) {

    public RadarContentItem {
        topic = clean(topic);
        type = type == null ? RadarContentType.WEB_PAGE : type;
        title = clean(title);
        summary = clean(summary);
        source = clean(source);
        publishedAt = clean(publishedAt);
        url = clean(url);
        eventKey = clean(eventKey);
        score = Math.max(0, Math.min(100, score));
        rationale = clean(rationale);
        discoveredAt = discoveredAt == null ? LocalDateTime.now() : discoveredAt;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
