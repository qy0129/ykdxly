package com.example.ilink.tools.planning;

import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Function Calling 截止时间倒计时工具。
 *
 * <p>计算当前时间与用户指定截止时间之间的差值，返回剩余时间（正数）、
 * 已到截止时间（零）或已超时（负数）的提示消息。正数时附带安慰信息，
 * 零时提示截止已到，负数时提示超时。</p>
 */
public final class DeadlineCountdownTool implements Tool {

    public static final String NAME = "deadline_countdown";

    private static final DateTimeFormatter DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter ISO_DATETIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private static final Map<Character, Integer> CHINESE_DIGITS = Map.of(
            '零', 0, '一', 1, '二', 2, '三', 3, '四', 4,
            '五', 5, '六', 6, '七', 7, '八', 8, '九', 9);

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "一", DayOfWeek.MONDAY, "二", DayOfWeek.TUESDAY, "三", DayOfWeek.WEDNESDAY,
            "四", DayOfWeek.THURSDAY, "五", DayOfWeek.FRIDAY, "六", DayOfWeek.SATURDAY,
            "日", DayOfWeek.SUNDAY, "天", DayOfWeek.SUNDAY);

    private final ToolDefinition definition;

    /** 创建截止时间倒计时工具。 */
    public DeadlineCountdownTool() {
        JsonObject properties = new JsonObject();
        properties.add("deadline", ToolDefinition.stringProperty(
                "用户给出的截止时间表达，支持多种格式。例如："
                + "标准格式 2026-07-24、2026-07-24 18:00、2026-07-24T18:00:00；"
                + "中文日期 今天、明天、后天、3天后、下周五、7月24日；"
                + "中文时间 六点、下午6点、六点半、今天下午6点、明天下午6点、下班（默认18:00）。"
                + "可以组合使用如 明天下午6点、后天上午10点半。"));
        properties.add("reference_time", ToolDefinition.stringProperty(
                "消息创建时间，格式 yyyy-MM-dd HH:mm:ss。不传时默认使用当前时间。"));
        this.definition = new ToolDefinition(
                NAME,
                "截止时间倒计时",
                "计算用户指定的截止时间与消息创建时间的差值，并给出人性化提示。"
                + "当还有剩余时间时告知还剩多久并安慰用户不要着急；"
                + "正好到截止时间时提示截止时间已到；"
                + "已超过截止时间时提示超时多久。",
                ToolDefinition.objectParameters(properties, "deadline"),
                true);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 解析截止时间并计算倒计时。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String deadlineStr = ToolArguments.requireString(arguments, "deadline").trim();

        LocalDateTime deadline = parseDeadline(deadlineStr);
        if (deadline == null) {
            return ToolResult.failure("无法识别截止时间\u201c" + deadlineStr
                    + "\u201d，请使用 yyyy-MM-dd（如 2026-07-24）或 yyyy-MM-dd HH:mm"
                    + "（如 2026-07-24 18:00）格式，也可以说 明天下午6点、下班 等。");
        }

        // 优先使用传入的消息创建时间，没有则用当前时间
        String referenceTimeStr = ToolArguments.string(arguments, "reference_time", "");
        LocalDateTime now;
        if (!referenceTimeStr.isBlank()) {
            try {
                now = LocalDateTime.parse(referenceTimeStr,
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception e) {
                return ToolResult.failure("reference_time 格式错误，请使用 yyyy-MM-dd HH:mm:ss 格式。");
            }
        } else {
            now = LocalDateTime.now();
        }
        long seconds = Duration.between(now, deadline).getSeconds();

        String message = formatMessage(seconds, deadlineStr);
        String status = seconds > 0 ? "positive" : (seconds == 0 ? "zero" : "negative");
        return ToolResult.success(message, new CountdownResult(deadlineStr, deadline.toString(), seconds, status));
    }

    /** 按剩余秒数生成中文提示。 */
    private static String formatMessage(long seconds, String deadlineExpr) {
        if (seconds > 0) {
            String remaining = formatDuration(seconds);
            return "距离\u201c" + deadlineExpr + "\u201d还有" + remaining ;
        } else if (seconds == 0) {
            return "截止时间\u201c" + deadlineExpr + "\u201d已经到了！";
        } else {
            String past = formatDuration(-seconds);
            return "很抱歉，你已经超过截止时间\u201c" + deadlineExpr + "\u201d" + past + "了！";
        }
    }

    /** 将秒数格式化为中文时长描述（天/小时/分/秒）。 */
    private static String formatDuration(long totalSeconds) {
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;

        StringBuilder sb = new StringBuilder();
        if (days > 0) sb.append(days).append("天");
        if (hours > 0) sb.append(hours).append("小时");
        if (minutes > 0) sb.append(minutes).append("分");
        if (secs > 0 || sb.isEmpty()) sb.append(secs).append("秒");
        return sb.toString();
    }

    // ========== 截止时间解析 ==========

    /** 尝试按多种格式解析截止时间字符串。 */
    static LocalDateTime parseDeadline(String text) {
        String value = text.replace("截止", "").replace("之前", "").trim();

        LocalDateTime std = parseStandard(value);
        if (std != null) return std;

        LocalDateTime chinese = parseChineseDateTime(value);
        if (chinese != null) return chinese;

        try {
            LocalDate date = LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atTime(23, 59, 59);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    /** 解析标准 ISO/日期时间格式。 */
    private static LocalDateTime parseStandard(String text) {
        try {
            return LocalDateTime.parse(text, ISO_DATETIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text, DATETIME_FORMAT);
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    /** 解析中文日期时间表达。 */
    static LocalDateTime parseChineseDateTime(String text) {
        LocalDate date = parseChineseDate(text);
        LocalTime time = parseChineseTime(text);

        if (date == null && time == null) return null;

        LocalDate today = LocalDate.now();
        if (date == null) date = today;
        if (time == null) time = LocalTime.of(23, 59, 59);

        LocalDateTime result = LocalDateTime.of(date, time);

        if (date.equals(today) && time.isBefore(LocalTime.now())
                && !hasExplicitDate(text)) {
            result = result.plusDays(1);
        }

        return result;
    }

    /** 解析中文日期部分。 */
    private static LocalDate parseChineseDate(String text) {
        LocalDate today = LocalDate.now();

        if (text.contains("今天") || text.contains("今日")) return today;
        if (text.contains("明天") || text.contains("明日")) return today.plusDays(1);
        if (text.contains("后天")) return today.plusDays(2);

        Pattern daysLater = Pattern.compile("(\\d+)\\s*天后");
        Matcher dm = daysLater.matcher(text);
        if (dm.find()) return today.plusDays(Integer.parseInt(dm.group(1)));

        if (text.contains("下周")) {
            for (var entry : WEEKDAYS.entrySet()) {
                if (text.contains(entry.getKey())) {
                    return today.plusWeeks(1).with(DayOfWeek.MONDAY)
                            .plusDays(entry.getValue().getValue() - 1L);
                }
            }
            return today.plusWeeks(1).with(DayOfWeek.MONDAY);
        }

        Pattern monthDay = Pattern.compile("(\\d{1,2})月(\\d{1,2})日?");
        Matcher mm = monthDay.matcher(text);
        if (mm.find()) {
            LocalDate date = LocalDate.of(today.getYear(),
                    Integer.parseInt(mm.group(1)), Integer.parseInt(mm.group(2)));
            return date.isBefore(today) ? date.plusYears(1) : date;
        }

        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ignored) {
        }

        return null;
    }

    /** 解析中文时间部分。 */
    private static LocalTime parseChineseTime(String text) {
        if (text.contains("下班")) return LocalTime.of(18, 0);

        int hourOffset = 0;
        boolean hasPeriod = false;
        if (text.contains("下午") || text.contains("傍晚")) {
            hourOffset = 12; hasPeriod = true;
        } else if (text.contains("晚上") || text.contains("夜晚") || text.contains("今晚")) {
            hourOffset = 12; hasPeriod = true;
        } else if (text.contains("凌晨")) {
            hourOffset = 0; hasPeriod = true;
        } else if (text.contains("早上") || text.contains("早晨") || text.contains("上午")) {
            hourOffset = 0; hasPeriod = true;
        } else if (text.contains("中午") || text.contains("午间")) {
            hourOffset = 0; hasPeriod = true;
        }

        int hour = -1;
        int minute = 0;

        Pattern chineseHour = Pattern.compile("([一二三四五六七八九十])\\s*点(?:\\s*半)?");
        Matcher chm = chineseHour.matcher(text);
        if (chm.find()) {
            hour = chineseDigitToInt(chm.group(1));
            if (text.contains("点半") || text.contains("点30")) minute = 30;
        }

        Pattern digitHour = Pattern.compile("(\\d{1,2})\\s*点(?:\\s*半)?");
        Matcher dhm = digitHour.matcher(text);
        if (dhm.find() && hour < 0) {
            hour = Integer.parseInt(dhm.group(1));
            if (text.contains("点半") || text.contains("点30")) minute = 30;
        }

        if (hour < 0) {
            Pattern timePattern = Pattern.compile("(\\d{1,2}):(\\d{2})");
            Matcher tm = timePattern.matcher(text);
            if (tm.find()) {
                hour = Integer.parseInt(tm.group(1));
                minute = Integer.parseInt(tm.group(2));
            }
        }

        if (hour < 0) return null;

        if (hourOffset == 12 && hour <= 12 && hour > 0) {
            if (hour != 12) hour += 12;
        }

        try {
            return LocalTime.of(hour, minute);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasExplicitDate(String text) {
        return text.contains("今天") || text.contains("今日") || text.contains("明天")
                || text.contains("明日") || text.contains("后天")
                || text.matches(".*\\d{1,2}月\\d{1,2}日?.*")
                || text.contains("下周")
                || text.matches(".*\\d+天后.*");
    }

    private static int chineseDigitToInt(String chinese) {
        if (chinese.length() == 1) {
            Integer digit = CHINESE_DIGITS.get(chinese.charAt(0));
            if (digit != null) return digit;
        }
        if ("十".equals(chinese)) return 10;
        if (chinese.startsWith("十")) return 10 + chineseDigitToInt(chinese.substring(1));
        if (chinese.endsWith("十")) return chineseDigitToInt(chinese.substring(0, chinese.length() - 1)) * 10;
        return -1;
    }

    /** 倒计时工具返回的结构化结果。 */
    public record CountdownResult(
            String deadlineExpression,
            String parsedDeadline,
            long remainingSeconds,
            String status) {
    }
}
