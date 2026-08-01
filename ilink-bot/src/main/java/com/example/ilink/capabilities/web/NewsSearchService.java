package com.example.ilink.capabilities.web;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 通过国内可用搜索源和 Bing RSS 查询实时新闻。 */
public final class NewsSearchService {

    private static final String TRUSTED_NEWS_SITES = "(site:news.cn OR site:xinhuanet.com "
            + "OR site:news.cctv.com OR site:chinanews.com.cn OR site:people.com.cn "
            + "OR site:thepaper.cn OR site:news.qq.com OR site:news.sina.com.cn OR site:news.163.com)";
    private static final Pattern REQUESTED_COUNT = Pattern.compile("([一二三四五六七八九十\\d]{1,3})\\s*(?:条|则|篇|个)");
    private final NewsProvider provider;

    public NewsSearchService(WebSearchService webSearchService) {
        this(webSearchService::search);
    }

    NewsSearchService(NewsProvider provider) {
        this.provider = provider;
    }

    public List<SearchResult> search(String query, int limit) throws IOException, InterruptedException {
        if (limit <= 0) return List.of();
        String topic = normalizeQuery(query);
        int resultLimit = requestedLimit(query, limit);
        int candidateLimit = Math.min(20, Math.max(limit, resultLimit * 3));
        List<String> queries = List.of(
                topic + " 今日新闻 " + TRUSTED_NEWS_SITES,
                topic + " 今日最新新闻");
        Map<String, SearchResult> unique = new LinkedHashMap<>();
        IOException lastFailure = null;

        for (int index = 0; index < queries.size() && unique.size() < resultLimit; index++) {
            try {
                List<SearchResult> results = provider.search(queries.get(index), candidateLimit);
                merge(unique, results, topic, resultLimit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (IOException | RuntimeException e) {
                lastFailure = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                String source = index == 0 ? "可信媒体" : "综合新闻源";
                System.err.println("[实时新闻] " + source + "查询失败: " + e.getMessage());
            }
        }
        if (!unique.isEmpty()) return List.copyOf(unique.values());
        if (lastFailure != null) throw lastFailure;
        return List.of();
    }

    static String normalizeQuery(String query) {
        if (query == null || query.isBlank()) return "最新热点";
        String value = query.replaceAll("(?i)\\s*when:1d\\s*", " ").trim();
        value = value.replaceFirst("^(?:(?:请|麻烦)?(?:帮我|给我)?\\s*)?(?:搜索|查询|查找|看看|获取)?(?:一下)?", "");
        value = value.replaceFirst("^(?:今天|今日)?(?:最重要|最热门|热度最高|最新|实时)?(?:的)?", "");
        value = REQUESTED_COUNT.matcher(value).replaceAll(" ");
        value = value.replaceAll("[，,。！？!?；;：:]+$", "").trim();
        value = value.replaceAll("(?:新闻|资讯|热搜|消息)$", "").trim();
        value = value.replaceAll("[，,。！？!?；;：:]+$", "").trim();
        return value.isBlank() ? "最新热点" : value;
    }

    static int requestedLimit(String query, int defaultLimit) {
        if (query == null) return defaultLimit;
        Matcher matcher = REQUESTED_COUNT.matcher(query);
        if (!matcher.find()) return defaultLimit;
        int requested = chineseNumber(matcher.group(1));
        return requested <= 0 ? defaultLimit : Math.min(defaultLimit, requested);
    }

    private static int chineseNumber(String value) {
        if (value.matches("\\d+")) return Integer.parseInt(value);
        if (!value.contains("十")) return digit(value.charAt(0));
        int tenIndex = value.indexOf('十');
        int tens = tenIndex == 0 ? 1 : digit(value.charAt(tenIndex - 1));
        int units = tenIndex == value.length() - 1 ? 0 : digit(value.charAt(tenIndex + 1));
        return tens * 10 + units;
    }

    private static int digit(char value) {
        return "零一二三四五六七八九".indexOf(value);
    }

    private static void merge(Map<String, SearchResult> target, List<SearchResult> results,
                              String topic, int limit) {
        if (results == null) return;
        for (SearchResult result : results) {
            if (result == null || result.title() == null || result.title().isBlank()) continue;
            if (!relevant(result, topic)) continue;
            String url = result.url() == null ? "" : result.url().trim();
            String key = url.isBlank() ? result.title().trim().toLowerCase(Locale.ROOT) : url;
            target.putIfAbsent(key, result);
            if (target.size() >= limit) return;
        }
    }

    private static boolean relevant(SearchResult result, String topic) {
        if ("最新热点".equals(topic)) return true;
        String content = (result.title() + " " + (result.summary() == null ? "" : result.summary()))
                .toLowerCase(Locale.ROOT);
        String normalizedTopic = topic.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        if (content.replaceAll("\\s+", "").contains(normalizedTopic)) return true;
        if (normalizedTopic.contains("人工智能") || "ai".equals(normalizedTopic)
                || normalizedTopic.contains("大模型") || normalizedTopic.contains("生成式")) {
            return List.of("人工智能", " ai ", "ai技术", "ai模型", "大模型", "机器学习", "深度学习",
                            "生成式", "智能体", "openai", "chatgpt", "deepseek", "通义千问", "qwen",
                            "claude", "gemini")
                    .stream().anyMatch(content::contains);
        }
        return false;
    }

    @FunctionalInterface
    interface NewsProvider {
        List<SearchResult> search(String query, int limit) throws IOException, InterruptedException;
    }
}
