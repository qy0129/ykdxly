package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RssSearchSupportTest {

    @Test
    void parsesResultsAndRejectsPrivateLinks() throws Exception {
        String xml = """
                <rss><channel>
                  <item><title>公开结果</title><description>摘要</description>
                    <link>https://example.com/news</link><source>示例来源</source>
                    <pubDate>Thu, 23 Jul 2026 08:00:00 GMT</pubDate></item>
                  <item><title>内网结果</title><link>http://127.0.0.1/admin</link></item>
                </channel></rss>
                """;
        var results = RssSearchSupport.parse(xml, 5);
        assertEquals(1, results.size());
        assertEquals("公开结果", results.getFirst().title());
        assertEquals("示例来源", results.getFirst().source());
    }

    @Test
    void validatesOnlyPublicHttpLinks() {
        assertTrue(RssSearchSupport.isPublicHttpUrl("https://example.com/a"));
        assertFalse(RssSearchSupport.isPublicHttpUrl("file:///etc/passwd"));
        assertFalse(RssSearchSupport.isPublicHttpUrl("http://192.168.1.2/a"));
        assertFalse(RssSearchSupport.isPublicHttpUrl("http://localhost/a"));
        assertFalse(RssSearchSupport.isPublicHttpUrl("http://[fd00::1]/a"));
    }
}
