package com.example.ilink.capabilities.web;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将哔哩哔哩视频网址转换为官方 b23.tv 短链接。 */
public final class ShortLinkService {

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^/(?:video/)?(BV[0-9A-Za-z]+|av\\d+)(?:/.*)?$");
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String shorten(String targetUrl) {
        if (!isHttpsUrl(targetUrl)) return targetUrl == null ? "" : targetUrl;
        return cache.computeIfAbsent(targetUrl, this::toBilibiliShortUrl);
    }

    private String toBilibiliShortUrl(String targetUrl) {
        URI uri = URI.create(targetUrl);
        String host = uri.getHost();
        if (host == null || !(host.equalsIgnoreCase("bilibili.com")
                || host.toLowerCase().endsWith(".bilibili.com"))) {
            return targetUrl;
        }
        Matcher matcher = VIDEO_ID_PATTERN.matcher(uri.getPath());
        return matcher.matches() ? "https://b23.tv/" + matcher.group(1) : targetUrl;
    }

    private boolean isHttpsUrl(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
