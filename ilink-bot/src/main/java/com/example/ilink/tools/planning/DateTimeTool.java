package com.example.ilink.tools.planning;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** 将自然语言中的截止日期转换为计划模块可使用的标准日期。 */
public final class DateTimeTool implements Tool {

    public static final String NAME = "resolve_date";

    private final ToolDefinition definition;

    public DateTimeTool() {
        JsonObject properties = new JsonObject();
        properties.add("date_expression", ToolDefinition.stringProperty(
                "截止日期表达，例如8月1日前、本周五、明天下午6点或2026-08-01"));
        this.definition = new ToolDefinition(
                NAME,
                "日期解析",
                "将自然语言日期解析为明确的截止日期，供后续任务规划使用。",
                ToolDefinition.objectParameters(properties, "date_expression"),
                true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String expression = ToolArguments.requireString(arguments, "date_expression").trim();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resolved = DateTimeParser.parse(expression, now);
        if (resolved == null) {
            return ToolResult.failure("无法识别截止时间“" + expression
                    + "”，请使用“8月1日前”“本周五”“3天后”或“2026-08-01”这样的格式。");
        }
        long remainingDays = Math.max(0, ChronoUnit.DAYS.between(now.toLocalDate(), resolved.toLocalDate()));
        DateResult result = new DateResult(expression, now.toLocalDate().toString(),
                resolved.toLocalDate().toString(), remainingDays);
        return ToolResult.success("截止日期=" + result.resolvedDate()
                + "，距离今天还有" + remainingDays + "天", result);
    }

    /** 日期解析结果，计划工具只需要使用 resolvedDate 字段。 */
    public record DateResult(
            String expression,
            String currentDate,
            String resolvedDate,
            long remainingDays) {
    }
}
