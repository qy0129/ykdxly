package com.example.ilink.feature.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShortLinkServiceTest {

    @Test
    void convertsBilibiliVideoToOfficialShortUrl() {
        ShortLinkService service = new ShortLinkService();

        assertEquals("https://b23.tv/BV1234567890",
                service.shorten("https://www.bilibili.com/video/BV1234567890?spm_id_from=333"));
    }

    @Test
    void keepsNonVideoUrlUnchanged() {
        ShortLinkService service = new ShortLinkService();
        String target = "https://search.bilibili.com/all?keyword=linear+algebra";

        assertEquals(target, service.shorten(target));
    }

    @Test
    void appliesShortLinkWhenFormattingBilibiliReply() {
        var service = new BilibiliSearchService(
                (query, limit) -> java.util.List.of(), ignored -> "https://b23.tv/BV123");

        String reply = service.formatReply(java.util.List.of(
                new com.example.ilink.model.SearchResult(
                        "线性代数课程", "", "哔哩哔哩", "",
                        "https://www.bilibili.com/video/BV123")));

        assertTrue(reply.contains("跳转链接：https://b23.tv/BV123"));
    }
}
