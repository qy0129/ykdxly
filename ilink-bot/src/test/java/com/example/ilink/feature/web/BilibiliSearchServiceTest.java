package com.example.ilink.feature.web;

import com.example.ilink.model.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BilibiliSearchServiceTest {

    @Test
    void keepsOnlyBilibiliVideoLinks() {
        var service = new BilibiliSearchService((query, limit) -> List.of(
                result("线性代数课程", "https://www.bilibili.com/video/BV123"),
                result("普通网页", "https://example.com/video/1"),
                result("站内非视频页", "https://www.bilibili.com/read/cv123")));

        List<SearchResult> results = service.search("线性代数 系统课程", "study");

        assertEquals(1, results.size());
        assertEquals("https://www.bilibili.com/video/BV123", results.getFirst().url());
    }

    @Test
    void fallsBackToOfficialSearchPage() {
        var service = new BilibiliSearchService((query, limit) -> List.of());

        List<SearchResult> results = service.search("周杰伦 歌曲", "music");

        assertEquals(1, results.size());
        assertTrue(results.getFirst().url().startsWith("https://search.bilibili.com/all?keyword="));
        assertTrue(results.getFirst().url().contains("%E5%91%A8%E6%9D%B0%E4%BC%A6"));
    }

    @Test
    void formatsAVisibleClickableTextLink() {
        var service = new BilibiliSearchService((query, limit) -> List.of());

        String reply = service.formatReply(List.of(result(
                "周杰伦歌曲", "https://www.bilibili.com/video/BV123")));

        assertTrue(reply.contains("跳转链接：https://www.bilibili.com/video/BV123"));
        assertFalse(reply.contains("[跳转链接]"));
    }

    private SearchResult result(String title, String url) {
        return new SearchResult(title, "", "哔哩哔哩", "", url);
    }
}
