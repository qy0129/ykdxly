package com.example.ilink.capabilities.radar;

import com.example.ilink.capabilities.web.SearchResult;

import java.time.LocalDateTime;
import java.util.List;

/** 持久保存的视频推荐游标，支持跨消息继续发送。 */
public record VideoFeedSession(
        String query,
        List<SearchResult> results,
        int cursor,
        List<SearchResult> lastBatch,
        LocalDateTime updatedAt) {

    public VideoFeedSession {
        query = query == null ? "" : query.trim();
        results = results == null ? List.of() : List.copyOf(results);
        cursor = Math.max(0, Math.min(cursor, results.size()));
        lastBatch = lastBatch == null ? List.of() : List.copyOf(lastBatch);
        updatedAt = updatedAt == null ? LocalDateTime.now() : updatedAt;
    }
}
