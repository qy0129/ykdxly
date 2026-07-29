package com.example.ilink.capabilities.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析搜狗公开搜索页，优先读取结果块中的真实来源地址。 */
final class SogouSearchSupport {
    private static final Pattern BLOCK_START = Pattern.compile("(?is)<div\\s+class=\"vrwrap\"");
    private static final Pattern TITLE = Pattern.compile("(?is)<h3[^>]*>.*?<a[^>]*>(.*?)</a>.*?</h3>");
    private static final Pattern SUMMARY = Pattern.compile(
            "(?is)<div[^>]*class=\"[^\"]*space-txt[^\"]*\"[^>]*>(.*?)</div>");
    private static final Pattern DATA_URL = Pattern.compile("(?is)data-url=\"(https?://[^\"]+)\"");
    private static final Pattern HREF = Pattern.compile("(?is)<h3[^>]*>.*?<a[^>]*href=\"([^\"]+)\"");
    private static final Pattern DATE = Pattern.compile(
            "(?is)<span[^>]*class=\"[^\"]*cite-date[^\"]*\"[^>]*>(.*?)</span>");

    private SogouSearchSupport() {
    }

    static List<SearchResult> parse(String html, int limit) {
        if (html == null || html.isBlank() || limit <= 0) return List.of();
        List<Integer> starts = new ArrayList<>();
        Matcher blocks = BLOCK_START.matcher(html);
        while (blocks.find()) starts.add(blocks.start());
        List<SearchResult> results = new ArrayList<>();
        for (int index = 0; index < starts.size() && results.size() < limit; index++) {
            int end = index + 1 < starts.size() ? starts.get(index + 1) : html.length();
            SearchResult result = parseBlock(html.substring(starts.get(index), end));
            if (result != null) results.add(result);
        }
        return List.copyOf(results);
    }

    private static SearchResult parseBlock(String block) {
        String title = group(TITLE, block);
        String summary = group(SUMMARY, block);
        String url = lastGroup(DATA_URL, block);
        if (url.isBlank()) {
            String href = group(HREF, block);
            url = href.startsWith("/") ? "https://www.sogou.com" + href : href;
        }
        title = clean(title);
        summary = clean(summary);
        url = entities(url).trim();
        if (title.isBlank() || !RssSearchSupport.isPublicHttpUrl(url)) return null;
        String source;
        try {
            source = URI.create(url).getHost();
        } catch (Exception ignored) {
            source = "";
        }
        return new SearchResult(title, summary, source, clean(group(DATE, block)), url);
    }

    private static String group(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String lastGroup(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        String result = "";
        while (matcher.find()) result = matcher.group(1);
        return result;
    }

    private static String clean(String value) {
        return entities(value.replaceAll("(?is)</?em[^>]*>", "")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?s)<[^>]+>", " "))
                .replaceAll("\\s+", " ").trim()
                .replace("「 ", "「").replace(" 」", "」")
                .replace("【 ", "【").replace(" 】", "】");
    }

    private static String entities(String value) {
        return value.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }
}
