package com.example.ilink.platform.network;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudflareTunnelTest {

    @Test
    void extractsQuickTunnelUrlFromCloudflaredLog() {
        String line = "2026-07-24T07:00:00Z INF +https://calm-river-42.trycloudflare.com";

        assertEquals("https://calm-river-42.trycloudflare.com",
                CloudflareTunnel.extractPublicUrl(line));
        assertEquals("", CloudflareTunnel.extractPublicUrl("connection registered"));
    }
}
