package com.example.ilink.capabilities.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WebSearchServiceTest {

    @Test
    void resolvesAbsoluteAndRelativeRedirects() throws Exception {
        URI current = URI.create("https://www.bing.com/search?q=test");

        assertEquals(URI.create("https://cn.bing.com/search?q=test"),
                WebSearchService.resolveRedirectUri(current, "https://cn.bing.com/search?q=test"));
        assertEquals(URI.create("https://www.bing.com/new?q=test"),
                WebSearchService.resolveRedirectUri(current, "/new?q=test"));
    }

    @Test
    void rejectsPrivateRedirectTarget() {
        URI current = URI.create("https://www.bing.com/search?q=test");

        assertThrows(IOException.class,
                () -> WebSearchService.resolveRedirectUri(current, "http://127.0.0.1/admin"));
    }
}
