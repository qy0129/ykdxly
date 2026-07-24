package com.example.ilink.feature.web;

import com.example.ilink.config.Config;
import com.example.ilink.model.SearchResult;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/** 基于 Google News RSS 的实时新闻查询，无需额外 API Key。 */
public final class NewsSearchService {

    private static final String NEWS_URL = "https://news.google.com/rss/search?hl=zh-CN&gl=CN&ceid=CN:zh-Hans&q=";
    private final HttpClient httpClient;

    public NewsSearchService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<SearchResult> search(String query, int limit) throws IOException, InterruptedException {
        String actualQuery = query == null || query.isBlank() ? "最新新闻" : query.trim();
        URI uri = URI.create(NEWS_URL + URLEncoder.encode(actualQuery, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                .header("User-Agent", "Mozilla/5.0 iLinkBot/1.0")
                .GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("新闻服务请求失败，HTTP " + response.statusCode());
        return RssSearchSupport.parse(response.body(), limit);
    }
}
