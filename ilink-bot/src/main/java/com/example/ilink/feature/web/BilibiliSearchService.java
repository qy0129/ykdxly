package com.example.ilink.feature.web;

import com.example.ilink.model.SearchResult;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

/** 搜索哔哩哔哩公开视频，并在联网搜索无结果时回退到官方搜索页。 */
public final class BilibiliSearchService {

    private static final int SEARCH_CANDIDATE_LIMIT = 8;
    private static final int RESULT_LIMIT = 3;
    private static final String SEARCH_URL = "https://search.bilibili.com/all?keyword=";
    private final SearchProvider searchProvider;
    private final UnaryOperator<String> linkShortener;

    public BilibiliSearchService(WebSearchService webSearchService, ShortLinkService shortLinkService) {
        this(webSearchService::search, shortLinkService::shorten);
    }

    BilibiliSearchService(SearchProvider searchProvider) {
        this(searchProvider, UnaryOperator.identity());
    }

    BilibiliSearchService(SearchProvider searchProvider, UnaryOperator<String> linkShortener) {
        this.searchProvider = searchProvider;
        this.linkShortener = linkShortener;
    }

    /** 返回具体视频；没有可靠视频时仍返回可点击的哔哩哔哩官方搜索入口。 */
    public List<SearchResult> search(String query, String category) {
        String keyword = normalizeQuery(query, category);
        try {
            List<SearchResult> candidates = searchProvider.search(
                    "site:bilibili.com/video " + keyword, SEARCH_CANDIDATE_LIMIT);
            List<SearchResult> results = filterBilibiliVideos(candidates);
            if (!results.isEmpty()) return results;
        } catch (Exception e) {
            System.err.println("[哔哩哔哩搜索] 联网搜索失败，回退到官方搜索页: " + e.getMessage());
        }
        return List.of(fallbackResult(keyword));
    }

    /** 微信纯文本消息不能隐藏 URL，因此用统一的短标签加可点击网址。 */
    public String formatReply(List<SearchResult> results) {
        StringBuilder reply = new StringBuilder("我为你找到了这些哔哩哔哩内容：\n");
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            reply.append(index + 1).append(". ").append(result.title()).append('\n')
                    .append("跳转链接：").append(linkShortener.apply(result.url()));
            if (index < results.size() - 1) reply.append("\n\n");
        }
        return reply.toString();
    }

    static String normalizeQuery(String query, String category) {
        String value = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        if (value.isBlank()) {
            value = switch (category == null ? "" : category.toLowerCase(Locale.ROOT)) {
                case "music" -> "热门音乐";
                case "series" -> "热门电视剧";
                case "study" -> "系统学习课程";
                default -> "热门视频";
            };
        }
        return value;
    }

    static List<SearchResult> filterBilibiliVideos(List<SearchResult> candidates) {
        List<SearchResult> results = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        for (SearchResult result : candidates == null ? List.<SearchResult>of() : candidates) {
            if (!isBilibiliVideoUrl(result.url()) || !seenUrls.add(result.url())) continue;
            results.add(result);
            if (results.size() >= RESULT_LIMIT) break;
        }
        return List.copyOf(results);
    }

    static boolean isBilibiliVideoUrl(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && (host.equalsIgnoreCase("bilibili.com")
                    || host.toLowerCase(Locale.ROOT).endsWith(".bilibili.com"))
                    && path != null
                    && path.startsWith("/video/");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private SearchResult fallbackResult(String keyword) {
        String url = SEARCH_URL + URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        return new SearchResult("在哔哩哔哩搜索“" + keyword + "”", "", "哔哩哔哩", "", url);
    }

    @FunctionalInterface
    interface SearchProvider {
        List<SearchResult> search(String query, int limit) throws Exception;
    }
}
