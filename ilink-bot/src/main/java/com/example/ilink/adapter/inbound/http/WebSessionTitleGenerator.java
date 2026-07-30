package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.messaging.MessagePart;

import java.util.List;
import java.util.Locale;

/** Creates a short local title without adding another model request to the reply path. */
final class WebSessionTitleGenerator {

    private static final int MAX_CODE_POINTS = 20;

    private WebSessionTitleGenerator() {
    }

    static String generate(List<MessagePart> parts, String assistantText) {
        String userText = firstUserText(parts);
        String source = normalize(userText);
        boolean vagueUserInput = isVague(source);
        if (vagueUserInput) source = normalize(assistantText);
        if (source.isBlank()) return "";

        String action = actionFor(vagueUserInput ? assistantText : userText);
        String topic = source
                .replaceFirst("^(请|麻烦|劳烦|能否|可否|可以)?(你)?(帮我|帮忙|协助我|告诉我|给我|继续)+", "")
                .replaceFirst("^(请|麻烦|劳烦)?(用一句话|简单|简要|详细)?(说明|介绍|讲解|解释|概括|总结)", "")
                .replaceFirst("^(请|麻烦|劳烦)", "")
                .replaceFirst("^(修改|修复|实现|开发|设计|生成|创建|分析|解释|检查|排查|总结|整理|优化|完善)", "")
                .replaceFirst("^(一下|这个|一下这个)", "")
                .replaceAll("(可以吗|行吗|谢谢|请处理|帮我处理)$", "")
                .trim();
        if (topic.isBlank()) topic = source;

        int topicLimit = Math.max(6, MAX_CODE_POINTS - codePoints(action));
        topic = truncate(topic, topicLimit);
        if (!action.isBlank() && !topic.endsWith(action) && !topic.contains(action)) topic += action;
        return truncate(topic, MAX_CODE_POINTS);
    }

    private static String firstUserText(List<MessagePart> parts) {
        if (parts == null) return "";
        for (MessagePart part : parts) {
            if (part instanceof MessagePart.Text text && text.text() != null && !text.text().isBlank()) {
                return text.text();
            }
            if (part instanceof MessagePart.Image image && image.fileName() != null) return image.fileName();
            if (part instanceof MessagePart.File file && file.fileName() != null) return file.fileName();
        }
        return "";
    }

    private static String normalize(String value) {
        String text = value(value)
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("https?://\\S+", " ")
                .replaceAll("[A-Za-z]:\\\\[^\\s，。！？]+", " ")
                .replaceAll("[\\r\\n\\t]+", " ")
                .replaceAll("[>#*_`~\\[\\]{}()（）]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        String[] clauses = text.split("[。！？!?；;：:]", 2);
        return clauses.length == 0 ? "" : clauses[0].replaceAll("^[，,、\\s]+|[，,、.\\s]+$", "");
    }

    private static String actionFor(String value) {
        String lower = value(value).toLowerCase(Locale.ROOT);
        if (containsAny(lower, "修复", "报错", "错误", "bug", "故障")) return "修复";
        if (containsAny(lower, "界面", "前端", "设计", "样式", "体验", "优化")) return "优化";
        if (containsAny(lower, "实现", "开发", "创建", "生成", "新增")) return "实现";
        if (containsAny(lower, "检查", "排查", "诊断", "原因")) return "排查";
        if (containsAny(lower, "总结", "整理", "概括")) return "总结";
        if (containsAny(lower, "分析", "解释", "说明", "介绍", "为什么", "如何", "怎么")) return "分析";
        return "讨论";
    }

    private static boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private static boolean isVague(String value) {
        String compact = value.replaceAll("\\s+", "");
        return compact.isBlank() || compact.matches("(继续|开始|好的|好|可以|确认|执行|继续任务)[吧啊呀。！!]*");
    }

    private static String truncate(String value, int maxCodePoints) {
        if (codePoints(value) <= maxCodePoints) return value;
        int end = value.offsetByCodePoints(0, maxCodePoints);
        String shortened = value.substring(0, end).replaceAll("[，,、.\\s]+$", "");
        int split = Math.max(shortened.lastIndexOf(' '), shortened.lastIndexOf('，'));
        if (split >= 8) shortened = shortened.substring(0, split);
        return shortened.trim();
    }

    private static int codePoints(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
