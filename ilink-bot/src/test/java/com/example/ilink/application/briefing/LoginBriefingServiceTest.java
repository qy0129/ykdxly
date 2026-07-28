package com.example.ilink.application.briefing;

import com.example.ilink.capabilities.web.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginBriefingServiceTest {

    @Test
    void formatsAtMostThreeRealtimeNewsItems() {
        List<SearchResult> results = List.of(
                new SearchResult("热点一", "这是热点一的具体内容", "媒体甲", "10:00", "https://example.com/1"),
                result("热点二", "媒体乙", "09:00", "https://example.com/2"),
                result("热点三", "媒体丙", "08:00", "https://example.com/3"),
                result("热点四", "媒体丁", "07:00", "https://example.com/4"));

        String text = LoginBriefingService.formatNews(results);

        assertTrue(text.startsWith("近期热点："));
        assertTrue(text.contains("热点一"));
        assertTrue(text.contains("内容：这是热点一的具体内容"));
        assertTrue(text.indexOf("内容：这是热点一的具体内容") < text.indexOf("网址：https://example.com/1"));
        assertTrue(text.contains("https://example.com/3"));
        assertEquals(false, text.contains("热点四"));
    }

    @Test
    void fallsBackToGoogleNewsWhenPublicSearchFails() {
        AtomicBoolean fallbackCalled = new AtomicBoolean();
        SearchResult fallbackResult = result(
                "回退热点", "Google News", "10:00", "https://example.com/fallback");

        List<SearchResult> results = LoginBriefingService.searchNews(
                (query, limit) -> { throw new IllegalStateException("primary unavailable"); },
                (query, limit) -> {
                    fallbackCalled.set(true);
                    return List.of(fallbackResult);
                });

        assertTrue(fallbackCalled.get());
        assertEquals(List.of(fallbackResult), results);
    }

    @Test
    void fallsBackToGoogleNewsWhenPublicSearchReturnsNoNews() {
        AtomicBoolean fallbackCalled = new AtomicBoolean();

        List<SearchResult> results = LoginBriefingService.searchNews(
                (query, limit) -> List.of(),
                (query, limit) -> {
                    fallbackCalled.set(true);
                    return List.of(result("今日热点", "公共搜索", "", "https://example.com/today"));
                });

        assertTrue(fallbackCalled.get());
        assertEquals("今日热点", results.getFirst().title());
    }

    private SearchResult result(String title, String source, String time, String url) {
        return new SearchResult(title, "", source, time, url);
    }
}
