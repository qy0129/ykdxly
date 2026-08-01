package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsSearchServiceTest {

    @Test
    void usesTrustedChineseSourcesWithoutGoogleSyntax() throws Exception {
        List<String> queries = new ArrayList<>();
        NewsSearchService service = new NewsSearchService((query, limit) -> {
            queries.add(query);
            return List.of(result("人工智能热点", "https://news.cn/a"));
        });

        List<SearchResult> results = service.search("人工智能 when:1d", 5);

        assertEquals(1, results.size());
        assertTrue(queries.getFirst().contains("site:news.cn"));
        assertTrue(queries.getFirst().contains("site:chinanews.com.cn"));
        assertFalse(queries.stream().anyMatch(query -> query.contains("Google") || query.contains("when:1d")));
    }

    @Test
    void fallsBackAndDeduplicatesNewsResults() throws Exception {
        List<String> queries = new ArrayList<>();
        NewsSearchService service = new NewsSearchService((query, limit) -> {
            queries.add(query);
            if (queries.size() == 1) throw new IOException("可信媒体暂不可用");
            return List.of(result("热点一", "https://example.com/1"),
                    result("热点一重复", "https://example.com/1"),
                    result("热点二", "https://example.com/2"));
        });

        List<SearchResult> results = service.search("今日热点", 5);

        assertEquals(2, queries.size());
        assertEquals(2, results.size());
        assertEquals("热点一", results.getFirst().title());
    }

    @Test
    void keepsArtificialIntelligenceTopicAndHonorsRequestedCount() throws Exception {
        List<String> queries = new ArrayList<>();
        NewsSearchService service = new NewsSearchService((query, limit) -> {
            queries.add(query);
            return List.of(
                    result("国内体育赛事今日开幕", "https://example.com/sport"),
                    result("人工智能大模型发布新版本", "https://example.com/ai-1"),
                    result("DeepSeek 推出新的推理模型", "https://example.com/ai-2"),
                    result("生成式 AI 应用进入制造业", "https://example.com/ai-3"),
                    result("国际油价出现波动", "https://example.com/finance"));
        });

        List<SearchResult> results = service.search("搜索今天最重要的三条人工智能新闻。", 5);

        assertEquals("人工智能", NewsSearchService.normalizeQuery("今天最重要的三条人工智能新闻"));
        assertEquals("人工智能", NewsSearchService.normalizeQuery("搜索今天最重要的三条人工智能新闻。"));
        assertEquals(3, NewsSearchService.requestedLimit("三条人工智能新闻", 5));
        assertTrue(queries.getFirst().startsWith("人工智能 今日新闻"));
        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> !result.url().contains("sport")
                && !result.url().contains("finance")));
    }

    private SearchResult result(String title, String url) {
        return new SearchResult(title, "摘要", "测试来源", "", url);
    }
}
