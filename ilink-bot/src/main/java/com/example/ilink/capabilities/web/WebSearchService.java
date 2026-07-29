package com.example.ilink.capabilities.web;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.web.SearchResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 真实联网搜索；配置 Tavily 时优先使用，否则回退到 Bing RSS。 */
public final class WebSearchService {

    private static final URI TAVILY_URL = URI.create("https://api.tavily.com/search");
    private static final String SO_URL = "https://www.so.com/s?q=";
    private static final String SOGOU_URL = "https://www.sogou.com/web?query=";
    private static final String BING_RSS_URL = "https://www.bing.com/search?format=rss&q=";
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Set<Integer> REDIRECT_STATUSES = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 3;
    private final HttpClient httpClient;

    public WebSearchService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<SearchResult> search(String query, int limit) throws IOException, InterruptedException {
        if (query == null || query.isBlank()) return List.of();
        if (!Config.TAVILY_API_KEY.isBlank()) {
            try {
                List<SearchResult> results = searchTavily(query.trim(), limit);
                if (!results.isEmpty()) return results;
            } catch (IOException | RuntimeException e) {
                System.err.println("[联网搜索] Tavily 失败，使用公共搜索回退: " + e.getMessage());
            }
        }
        if (containsChinese(query)) {
            try {
                URI uri = URI.create(SO_URL + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
                List<SearchResult> results = SoSearchSupport.parse(get(uri), limit);
                if (!results.isEmpty()) return results;
            } catch (IOException | RuntimeException e) {
                System.err.println("[联网搜索] 360 搜索失败，尝试搜狗回退: " + e.getMessage());
            }
            try {
                URI uri = URI.create(SOGOU_URL + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
                List<SearchResult> results = SogouSearchSupport.parse(get(uri), limit);
                if (!results.isEmpty()) return results;
            } catch (IOException | RuntimeException e) {
                System.err.println("[联网搜索] 搜狗失败，使用 Bing RSS 回退: " + e.getMessage());
            }
        }
        URI uri = URI.create(BING_RSS_URL + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8));
        return RssSearchSupport.parse(get(uri), limit);
    }

    private boolean containsChinese(String value) {
        return value.codePoints().anyMatch(codePoint -> codePoint >= 0x4E00 && codePoint <= 0x9FFF);
    }

    private List<SearchResult> searchTavily(String query, int limit) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("api_key", Config.TAVILY_API_KEY);
        body.addProperty("query", query);
        body.addProperty("search_depth", "advanced");
        body.addProperty("max_results", limit);
        body.addProperty("include_answer", false);
        HttpRequest request = HttpRequest.newBuilder(TAVILY_URL)
                .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode());
        JsonArray items = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("results");
        if (items == null) return List.of();
        List<SearchResult> results = new ArrayList<>();
        for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            String url = string(item, "url");
            if (!RssSearchSupport.isPublicHttpUrl(url)) continue;
            results.add(new SearchResult(string(item, "title"), string(item, "content"),
                    URI.create(url).getHost(), "", url));
        }
        return List.copyOf(results);
    }

    private String get(URI uri) throws IOException, InterruptedException {
        URI current = uri;
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                    .header("User-Agent", BROWSER_USER_AGENT)
                    .GET().build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) return response.body();
            if (!REDIRECT_STATUSES.contains(response.statusCode())) {
                throw new IOException("公共搜索请求失败，HTTP " + response.statusCode());
            }
            if (redirectCount == MAX_REDIRECTS) {
                throw new IOException("公共搜索重定向次数过多");
            }
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IOException("公共搜索重定向缺少 Location"));
            current = resolveRedirectUri(current, location);
        }
        throw new IOException("公共搜索请求失败");
    }

    static URI resolveRedirectUri(URI current, String location) throws IOException {
        try {
            URI target = current.resolve(location);
            if (!RssSearchSupport.isPublicHttpUrl(target.toString())) {
                throw new IOException("公共搜索拒绝不安全的重定向地址");
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw new IOException("公共搜索重定向地址无效", e);
        }
    }

    private String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
