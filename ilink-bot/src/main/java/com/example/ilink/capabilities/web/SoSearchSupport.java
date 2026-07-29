package com.example.ilink.capabilities.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 解析 360 搜索公开结果中的标题、摘要和真实来源地址。 */
final class SoSearchSupport {
    private static final Pattern RESULT = Pattern.compile(
            "(?is)<li[^>]*class=\"[^\"]*res-list[^\"]*\"[^>]*>(.*?)</li>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<h3[^>]*>.*?<a[^>]*>(.*?)</a>.*?</h3>");
    private static final Pattern SUMMARY = Pattern.compile(
            "(?is)<p[^>]*class=\"[^\"]*res-desc[^\"]*\"[^>]*>(.*?)</p>");
    private static final Pattern DIRECT_URL = Pattern.compile("(?is)data-mdurl=\"(https?://[^\"]+)\"");
    private static final Pattern HREF = Pattern.compile(
            "(?is)<h3[^>]*>.*?<a[^>]*href=\"([^\"]+)\"");

    private SoSearchSupport() {
    }

    static List<SearchResult> parse(String html, int limit) {
        if (html == null || html.isBlank() || limit <= 0) return List.of();
        List<SearchResult> results = new ArrayList<>();
        Matcher matcher = RESULT.matcher(html);
        while (matcher.find() && results.size() < limit) {
            SearchResult result = parseResult(matcher.group(1));
            if (result != null) results.add(result);
        }
        return List.copyOf(results);
    }

    private static SearchResult parseResult(String block) {
        String title = clean(group(TITLE, block));
        String summary = clean(group(SUMMARY, block));
        String url = entities(group(DIRECT_URL, block));
        if (url.isBlank()) url = entities(group(HREF, block));
        if (title.isBlank() || !RssSearchSupport.isPublicHttpUrl(url)) return null;
        String source;
        try {
            source = URI.create(url).getHost();
        } catch (Exception ignored) {
            source = "";
        }
        return new SearchResult(title, summary, source, "", url);
    }

    private static String group(Pattern pattern, String value) {
        Matcher matcher = pattern.matcher(value);
        return matcher.find() ? matcher.group(1) : "";
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
