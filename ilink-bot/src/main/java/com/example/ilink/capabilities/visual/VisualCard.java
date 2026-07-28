package com.example.ilink.capabilities.visual;

import java.awt.Color;

/** 微信图片查看器中展示的一页视觉卡片。 */
public record VisualCard(
        String title,
        String subtitle,
        String body,
        String footer,
        String qrUrl,
        String qrLabel,
        Color accent) {

    public VisualCard {
        title = clean(title);
        subtitle = clean(subtitle);
        body = clean(body);
        footer = clean(footer);
        qrUrl = clean(qrUrl);
        qrLabel = clean(qrLabel);
        accent = accent == null ? new Color(45, 122, 98) : accent;
    }

    public static VisualCard of(String title, String subtitle, String body) {
        return new VisualCard(title, subtitle, body, "", "", "", null);
    }

    public VisualCard withQr(String url, String label) {
        return new VisualCard(title, subtitle, body, footer, url, label, accent);
    }

    public VisualCard withFooter(String value) {
        return new VisualCard(title, subtitle, body, value, qrUrl, qrLabel, accent);
    }

    public VisualCard withAccent(Color value) {
        return new VisualCard(title, subtitle, body, footer, qrUrl, qrLabel, value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
