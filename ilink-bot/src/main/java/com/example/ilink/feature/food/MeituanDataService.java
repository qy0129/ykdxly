package com.example.ilink.feature.food;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从美团 H5 搜索页中严格匹配门店名称附近的门店 ID。 */
public final class MeituanDataService {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Pattern STORE_ID = Pattern.compile(
            "\\\"(?:wmPoiId|wm_poi_id|poiId)\\\"\\s*:\\s*\\\"?(\\d+)\\\"?");
    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private final HttpClient client;

    public MeituanDataService(HttpClient client) {
        this.client = client;
    }

    public String findStoreId(String keyword, String longitude, String latitude) {
        try {
            String url = "https://h5.waimai.meituan.com/waimai/msearch/search?key=" + encode(keyword)
                    + "&lng=" + encode(longitude) + "&lat=" + encode(latitude);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile")
                    .header("Referer", "https://h5.waimai.meituan.com/")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "";
            return findStoreId(response.body(), keyword);
        } catch (Exception ignored) {
            return "";
        }
    }

    static String findStoreId(String body, String keyword) {
        if (body == null || body.isBlank()) return "";
        String decoded = decodeUnicode(body).replace("\\\"", "\"");
        String expected = normalize(keyword);
        Matcher matcher = STORE_ID.matcher(decoded);
        while (matcher.find()) {
            int from = Math.max(0, matcher.start() - 1200);
            int to = Math.min(decoded.length(), matcher.end() + 1200);
            if (normalize(decoded.substring(from, to)).contains(expected)) {
                return matcher.group(1);
            }
        }
        return "";
    }

    private static String decodeUnicode(String value) {
        Matcher matcher = UNICODE.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            char decoded = (char) Integer.parseInt(matcher.group(1), 16);
            matcher.appendReplacement(result, Matcher.quoteReplacement(String.valueOf(decoded)));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
                .replaceAll("[\\s（）()·._\\-\\\"'/:,，。]", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
