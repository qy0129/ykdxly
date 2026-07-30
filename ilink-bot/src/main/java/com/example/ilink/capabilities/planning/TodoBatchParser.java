package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 处理高频、低风险的复合待办快路径，避免把整句话存成一个标题。 */
public final class TodoBatchParser {
    private static final Pattern SEPARATOR = Pattern.compile("\\s*[，,；;。]\\s*");
    private static final Pattern TIME_MARKER = Pattern.compile(
            "(今天|明天|后天|本周|下周|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}日|[上下]午|\\d{1,2}点|\\d{1,2}[:：]\\d{2})");
    private static final Pattern DATE_MARKER = Pattern.compile(
            "(今天|今日|明天|明日|后天|本周|这周|下周|下下周|周[一二三四五六日天]|星期[一二三四五六日天]|\\d{1,2}月\\d{1,2}(?:日|号)?)");

    public List<TodoDraft> parse(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = text.trim();
        String[] pieces = SEPARATOR.split(normalized);
        List<TodoDraft> result = new ArrayList<>();
        String inheritedDate = "";
        for (String piece : pieces) {
            String source = piece.trim();
            if (source.isBlank()) continue;
            var dateMatcher = DATE_MARKER.matcher(source);
            if (dateMatcher.find()) inheritedDate = dateMatcher.group(1);
            String title = cleanTitle(source);
            if (title.isBlank()) continue;
            String parseSource = DATE_MARKER.matcher(source).find() || inheritedDate.isBlank()
                    ? source : inheritedDate + " " + source;
            LocalDateTime dueAt = DateTimeParser.parse(parseSource);
            result.add(new TodoDraft("todo_" + (result.size() + 1), source, title, dueAt));
        }
        return List.copyOf(result);
    }

    public boolean looksLikeCompound(String text) {
        if (text == null || text.isBlank()) return false;
        return SEPARATOR.matcher(text).find()
                && (text.contains("提醒") || text.contains("记得") || text.contains("别忘了")
                || text.contains("待办") || TIME_MARKER.matcher(text).find());
    }

    private String cleanTitle(String source) {
        String title = source
                .replaceFirst("^(请)?(帮我)?(添加|新增|创建|记)(一个|个)?待办[：:，, ]*", "")
                .replaceFirst("^待办[：:，, ]*", "")
                .replaceFirst("^(提醒我|请提醒我|别忘了|记得|记一下|帮我记住)[：:，, ]*", "")
                .replaceAll("(今天|今日|明天|明日|后天|\\d+天后|本周|这周|下周|下下周|周[一二三四五六日天]|星期[一二三四五六日天])", "")
                .replaceAll("(?:(?:\\d{4})年)?\\d{1,2}月\\d{1,2}(?:日|号)?", "")
                .replaceAll("(上午|中午|下午|傍晚|晚上|今晚)?[零一二三四五六七八九十两\\d]{1,3}点(半)?", "")
                .replaceAll("\\d{1,2}[：:]\\d{2}", "")
                .replaceAll("^(提醒我|请提醒我|提醒|安排|记得|别忘了)[：:，, ]*", "")
                .replaceAll("[，, ]+", " ")
                .trim();
        return title;
    }
}
