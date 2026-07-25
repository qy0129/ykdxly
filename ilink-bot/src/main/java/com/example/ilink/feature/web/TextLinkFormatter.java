package com.example.ilink.feature.web;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将文字回复中的所有网页地址统一渲染为固定的可点击文字。 */
public final class TextLinkFormatter {

    private static final String LABEL = "点击此链接跳转";
    private static final Pattern LINK = Pattern.compile(
            "\\[[^\\]\\r\\n]+]\\((https?://[^\\s)]+)\\)"
                    + "|(https?://[^\\s<>()\\]}>，。！？；：“”‘’]+)",
            Pattern.CASE_INSENSITIVE);

    private TextLinkFormatter() {
    }

    public static String format(String text) {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        Matcher matcher = LINK.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String url = matcher.group(1) == null ? matcher.group(2) : matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement("[" + LABEL + "](" + url + ")"));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
