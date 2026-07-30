package com.example.ilink.capabilities.radar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取哔哩哔哩公开视频简介与公开字幕，不下载媒体文件。 */
public final class BilibiliVideoContentService {
    private static final Pattern BVID = Pattern.compile("(?i)(BV[0-9A-Za-z]{10})");
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_MATERIAL_CHARS = 24000;
    private static final Set<String> CONTENT_HOST_SUFFIXES = Set.of(
            "bilibili.com", "bilivideo.com", "hdslb.com", "biliapi.net");

    private final JsonGateway gateway;

    public BilibiliVideoContentService(HttpClient client) {
        this(uri -> fetchJson(client, uri));
    }

    BilibiliVideoContentService(JsonGateway gateway) {
        this.gateway = gateway;
    }

    public InterestRadarService.VideoMaterial load(com.example.ilink.capabilities.web.SearchResult video)
            throws Exception {
        String bvid = extractBvid(video.url());
        if (bvid.isBlank()) return description(video.summary(), "搜索结果公开描述");

        JsonObject view = data(gateway.fetch(URI.create(
                "https://api.bilibili.com/x/web-interface/view?bvid=" + encode(bvid))));
        String description = string(view, "desc");
        JsonArray pages = view.getAsJsonArray("pages");
        if (pages == null || pages.isEmpty()) return description(description, "视频公开简介");
        long cid = longValue(pages.get(0).getAsJsonObject(), "cid");
        if (cid <= 0) return description(description, "视频公开简介");

        JsonObject player = data(gateway.fetch(URI.create(
                "https://api.bilibili.com/x/player/v2?bvid=" + encode(bvid) + "&cid=" + cid)));
        String subtitleUrl = firstSubtitleUrl(player);
        if (subtitleUrl.isBlank()) return description(description, "视频未提供公开字幕，使用公开简介");

        URI subtitleUri = allowedContentUri(subtitleUrl);
        String transcript = transcript(gateway.fetch(subtitleUri));
        if (transcript.isBlank()) return description(description, "公开字幕为空，使用公开简介");
        return new InterestRadarService.VideoMaterial(transcript, "public_subtitle",
                "公开视频字幕，时间点来自字幕文件");
    }

    static String extractBvid(String value) {
        Matcher matcher = BVID.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    static String transcript(JsonObject subtitle) {
        JsonArray body = subtitle == null ? null : subtitle.getAsJsonArray("body");
        if (body == null) return "";
        StringBuilder text = new StringBuilder();
        for (JsonElement element : body) {
            if (!element.isJsonObject()) continue;
            JsonObject line = element.getAsJsonObject();
            String content = string(line, "content").replaceAll("\\s+", " ").trim();
            if (content.isBlank()) continue;
            long seconds = Math.max(0, Math.round(doubleValue(line, "from")));
            text.append('[').append(timestamp(seconds)).append("] ").append(content).append('\n');
            if (text.length() >= MAX_MATERIAL_CHARS) break;
        }
        return text.substring(0, Math.min(text.length(), MAX_MATERIAL_CHARS)).trim();
    }

    private static InterestRadarService.VideoMaterial description(String value, String note) {
        return new InterestRadarService.VideoMaterial(value == null ? "" : value,
                "public_description", note);
    }

    private static JsonObject data(JsonObject response) {
        if (response == null || !response.has("code") || response.get("code").getAsInt() != 0) {
            throw new IllegalStateException("哔哩哔哩公开接口返回失败");
        }
        JsonObject value = response.getAsJsonObject("data");
        if (value == null) throw new IllegalStateException("哔哩哔哩公开接口缺少数据");
        return value;
    }

    private static String firstSubtitleUrl(JsonObject player) {
        JsonObject subtitle = player.getAsJsonObject("subtitle");
        JsonArray values = subtitle == null ? null : subtitle.getAsJsonArray("subtitles");
        if (values == null || values.isEmpty()) return "";
        for (JsonElement value : values) {
            String url = string(value.getAsJsonObject(), "subtitle_url");
            if (!url.isBlank()) return url;
        }
        return "";
    }

    private static URI allowedContentUri(String value) {
        URI uri = URI.create(value.startsWith("//") ? "https:" + value : value);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = "https".equalsIgnoreCase(uri.getScheme())
                && CONTENT_HOST_SUFFIXES.stream().anyMatch(
                suffix -> host.equals(suffix) || host.endsWith("." + suffix));
        if (!allowed) throw new IllegalArgumentException("字幕地址不属于允许的哔哩哔哩域名");
        return uri;
    }

    private static JsonObject fetchJson(HttpClient client, URI uri) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12))
                .header("User-Agent", "Mozilla/5.0 iLinkBot-Radar/1.0")
                .header("Referer", "https://www.bilibili.com/").GET().build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            response.body().close();
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        byte[] bytes;
        try (InputStream body = response.body()) {
            bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
        }
        if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalStateException("响应超过 1 MB");
        JsonElement parsed = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        if (!parsed.isJsonObject()) throw new IllegalStateException("响应不是 JSON 对象");
        return parsed.getAsJsonObject();
    }

    private static String timestamp(long seconds) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600,
                seconds % 3600 / 60, seconds % 60);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String string(JsonObject object, String name) {
        return object != null && object.has(name) && !object.get(name).isJsonNull()
                ? object.get(name).getAsString() : "";
    }

    private static long longValue(JsonObject object, String name) {
        return object != null && object.has(name) ? object.get(name).getAsLong() : 0;
    }

    private static double doubleValue(JsonObject object, String name) {
        return object != null && object.has(name) ? object.get(name).getAsDouble() : 0;
    }

    @FunctionalInterface
    interface JsonGateway {
        JsonObject fetch(URI uri) throws Exception;
    }
}
