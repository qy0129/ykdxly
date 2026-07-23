package com.example.ilink.tools.planning;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 计算指定截止时间与当前时间之间的剩余时长。 */
public final class DeadlineCountdownTool implements Tool {

    public static final String NAME = "deadline_countdown";
    private static final DateTimeFormatter REFERENCE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolDefinition definition;

    public DeadlineCountdownTool() {
        JsonObject properties = new JsonObject();
        properties.add("deadline", ToolDefinition.stringProperty(
                "截止时间，例如8月1日前、本周五下午6点、明天下午6点或2026-08-01 18:00"));
        properties.add("reference_time", ToolDefinition.stringProperty(
                "消息创建时间，格式yyyy-MM-dd HH:mm:ss；不传时使用当前时间"));
        this.definition = new ToolDefinition(
                NAME,
                "截止时间倒计时",
                "解析用户指定的截止时间，并计算距离该时间还有多久或已经超时多久。",
                ToolDefinition.objectParameters(properties, "deadline"),
                true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String expression = ToolArguments.requireString(arguments, "deadline").trim();
        LocalDateTime now = resolveReferenceTime(ToolArguments.string(arguments, "reference_time", ""));
        if (now == null) {
            return ToolResult.failure("reference_time 格式错误，请使用 yyyy-MM-dd HH:mm:ss 格式。");
        }

        LocalDateTime deadline = DateTimeParser.parse(expression, now);
        if (deadline == null) {
            return ToolResult.failure("无法识别截止时间“" + expression
                    + "”，请使用“8月1日前”“本周五”“明天下午6点”或“2026-08-01 18:00”这样的格式。");
        }

        long seconds = Duration.between(now, deadline).getSeconds();
        String status = seconds > 0 ? "positive" : seconds == 0 ? "zero" : "negative";
        return ToolResult.success(formatMessage(seconds, expression),
                new CountdownResult(expression, deadline.toString(), seconds, status));
    }

    private static LocalDateTime resolveReferenceTime(String value) {
        if (value == null || value.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return LocalDateTime.parse(value, REFERENCE_TIME_FORMAT);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static String formatMessage(long seconds, String expression) {
        if (seconds > 0) {
            return "距离“" + expression + "”还有" + formatDuration(seconds) + "。";
        }
        if (seconds == 0) {
            return "截止时间“" + expression + "”已经到了。";
        }
        return "已经超过截止时间“" + expression + "”" + formatDuration(-seconds) + "。";
    }

    private static String formatDuration(long totalSeconds) {
        long days = totalSeconds / 86_400;
        long hours = totalSeconds % 86_400 / 3_600;
        long minutes = totalSeconds % 3_600 / 60;
        long seconds = totalSeconds % 60;
        StringBuilder result = new StringBuilder();
        if (days > 0) result.append(days).append("天");
        if (hours > 0) result.append(hours).append("小时");
        if (minutes > 0) result.append(minutes).append("分钟");
        if (seconds > 0 || result.isEmpty()) result.append(seconds).append("秒");
        return result.toString();
    }

    /** 倒计时工具返回的结构化结果。 */
    public record CountdownResult(
            String deadlineExpression,
            String parsedDeadline,
            long remainingSeconds,
            String status) {
    }
}
