package com.example.ilink.tools.planning;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Function Calling 日期计算工具。 */
public final class DateTimeTool implements Tool {

    public static final String NAME = "resolve_date";

    private static final Pattern DAYS_LATER_PATTERN = Pattern.compile("(\\d+)\\s*天后");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})日?");
    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "一", DayOfWeek.MONDAY,
            "二", DayOfWeek.TUESDAY,
            "三", DayOfWeek.WEDNESDAY,
            "四", DayOfWeek.THURSDAY,
            "五", DayOfWeek.FRIDAY,
            "六", DayOfWeek.SATURDAY,
            "日", DayOfWeek.SUNDAY);

    private final ToolDefinition definition;

    /** 创建日期计算工具。 */
    public DateTimeTool() {
        JsonObject properties = new JsonObject();
        properties.add("date_expression", ToolDefinition.stringProperty(
                "用户给出的截止日期，例如后天、3天后、下周五或2026-07-24"));
        this.definition = new ToolDefinition(
                NAME,
                "日期计算",
                "把今天、明天、后天、几天后或下周几转换为明确日期，用于后续任务规划。",
                ToolDefinition.objectParameters(properties, "date_expression"),
                true);
    }

    /** 返回日期计算工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 解析自然语言日期并返回明确截止日期。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String expression = ToolArguments.requireString(arguments, "date_expression").trim();
        LocalDate today = LocalDate.now();
        LocalDate resolved = resolve(expression, today);
        if (resolved == null) {
            return ToolResult.failure("无法识别截止时间“" + expression
                    + "”，请使用“后天”“3天后”或“2026-07-24”这样的格式。");
        }
        long remainingDays = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(today, resolved));
        DateResult result = new DateResult(expression, today.toString(), resolved.toString(), remainingDays);
        return ToolResult.success("截止日期=" + result.resolvedDate()
                + "，距离今天还有" + remainingDays + "天", result);
    }

    /** 按常见中文时间表达式计算日期。 */
    private LocalDate resolve(String expression, LocalDate today) {
        String value = expression.replace("截止", "").replace("之前", "").trim();
        if (value.contains("今天") || "今日".equals(value)) return today;
        if (value.contains("后天")) return today.plusDays(2);
        if (value.contains("明天") || "明日".equals(value)) return today.plusDays(1);

        Matcher daysLater = DAYS_LATER_PATTERN.matcher(value);
        if (daysLater.find()) {
            return today.plusDays(Integer.parseInt(daysLater.group(1)));
        }

        if (value.startsWith("下周")) {
            String weekdayText = value.substring(2, Math.min(3, value.length()));
            DayOfWeek target = WEEKDAYS.get(weekdayText);
            if (target != null) {
                LocalDate nextWeekMonday = today.plusWeeks(1).with(DayOfWeek.MONDAY);
                return nextWeekMonday.plusDays(target.getValue() - 1L);
            }
        }

        Matcher monthDay = MONTH_DAY_PATTERN.matcher(value);
        if (monthDay.find()) {
            LocalDate date = LocalDate.of(today.getYear(),
                    Integer.parseInt(monthDay.group(1)), Integer.parseInt(monthDay.group(2)));
            return date.isBefore(today) ? date.plusYears(1) : date;
        }

        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 日期工具返回给工作流的结构化结果。 */
    public record DateResult(
            String expression,
            String currentDate,
            String resolvedDate,
            long remainingDays) {
    }
}
