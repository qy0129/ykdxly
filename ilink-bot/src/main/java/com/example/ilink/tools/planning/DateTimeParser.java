package com.example.ilink.tools.planning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一解析用户输入的日期和时间表达。
 *
 * <p>计划生成和倒计时都必须使用本类，避免同一种日期表达在不同功能中得到不同结果。</p>
 */
public final class DateTimeParser {

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);
    private static final Pattern DAYS_LATER_PATTERN = Pattern.compile("(\\d+)天后");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})(?:日|号)?");
    private static final Pattern CLOCK_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})");
    private static final Pattern HOUR_PATTERN = Pattern.compile("([零一二三四五六七八九十两\\d]{1,3})点(半)?");

    private static final Map<String, DayOfWeek> WEEKDAYS = Map.of(
            "一", DayOfWeek.MONDAY,
            "二", DayOfWeek.TUESDAY,
            "三", DayOfWeek.WEDNESDAY,
            "四", DayOfWeek.THURSDAY,
            "五", DayOfWeek.FRIDAY,
            "六", DayOfWeek.SATURDAY,
            "日", DayOfWeek.SUNDAY,
            "天", DayOfWeek.SUNDAY);

    private DateTimeParser() {
    }

    /** 使用当前时间解析用户输入，无法解析时返回 {@code null}。 */
    public static LocalDateTime parse(String expression) {
        return parse(expression, LocalDateTime.now());
    }

    /**
     * 解析用户输入的截止时间。
     * 支持标准日期、相对日期、周几、中文月日和中文时刻，例如“8 月 1 日前”“本周五下午六点”。
     */
    static LocalDateTime parse(String expression, LocalDateTime referenceTime) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        String value = normalize(expression);
        LocalDateTime standard = parseStandard(value);
        if (standard != null) {
            return standard;
        }

        LocalDate date = parseDate(value, referenceTime.toLocalDate());
        LocalTime time = parseTime(value);
        if (date == null && time == null) {
            return null;
        }

        if (date == null) {
            date = referenceTime.toLocalDate();
        }
        if (time == null) {
            time = END_OF_DAY;
        }

        LocalDateTime result = LocalDateTime.of(date, time);
        if (!hasDateExpression(value) && result.isBefore(referenceTime)) {
            return result.plusDays(1);
        }
        return result;
    }

    /** 删除不影响日期含义的空格和截止语气词。 */
    private static String normalize(String expression) {
        String value = expression.replaceAll("\\s+", "").replace('：', ':').trim();
        return value.replace("截止时间", "")
                .replace("截止", "")
                .replace("之前", "")
                .replace("以前", "")
                .replace("前完成", "")
                .replace("前", "")
                .trim();
    }

    private static LocalDateTime parseStandard(String value) {
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE).atTime(END_OF_DAY);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static LocalDate parseDate(String value, LocalDate today) {
        if (value.contains("今天") || value.contains("今日")) return today;
        if (value.contains("明天") || value.contains("明日")) return today.plusDays(1);
        if (value.contains("后天")) return today.plusDays(2);

        Matcher daysLater = DAYS_LATER_PATTERN.matcher(value);
        if (daysLater.find()) {
            return today.plusDays(Long.parseLong(daysLater.group(1)));
        }

        LocalDate weekDate = parseWeekday(value, today);
        if (weekDate != null) {
            return weekDate;
        }

        Matcher monthDay = MONTH_DAY_PATTERN.matcher(value);
        if (monthDay.find()) {
            int year = monthDay.group(1) == null ? today.getYear() : Integer.parseInt(monthDay.group(1));
            int month = Integer.parseInt(monthDay.group(2));
            int day = Integer.parseInt(monthDay.group(3));
            try {
                LocalDate date = LocalDate.of(year, month, day);
                return monthDay.group(1) == null && date.isBefore(today) ? date.plusYears(1) : date;
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        return null;
    }

    private static LocalDate parseWeekday(String value, LocalDate today) {
        Matcher matcher = Pattern.compile("(本周|这周|下周|下下周|周|星期)([一二三四五六日天])").matcher(value);
        if (!matcher.find()) {
            return null;
        }
        DayOfWeek weekday = WEEKDAYS.get(matcher.group(2));
        String prefix = matcher.group(1);
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        if ("下周".equals(prefix)) monday = monday.plusWeeks(1);
        if ("下下周".equals(prefix)) monday = monday.plusWeeks(2);
        LocalDate result = monday.plusDays(weekday.getValue() - 1L);
        return ("周".equals(prefix) || "星期".equals(prefix)) && result.isBefore(today)
                ? result.plusWeeks(1) : result;
    }

    private static LocalTime parseTime(String value) {
        if (value.contains("下班")) return LocalTime.of(18, 0);

        Matcher clock = CLOCK_PATTERN.matcher(value);
        if (clock.find()) {
            return createTime(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)), value);
        }

        Matcher hour = HOUR_PATTERN.matcher(value);
        if (!hour.find()) {
            return null;
        }
        int number = parseChineseNumber(hour.group(1));
        return number < 0 ? null : createTime(number, hour.group(2) == null ? 0 : 30, value);
    }

    private static LocalTime createTime(int hour, int minute, String value) {
        if (value.contains("下午") || value.contains("傍晚") || value.contains("晚上") || value.contains("今晚")) {
            if (hour > 0 && hour < 12) hour += 12;
        }
        try {
            return LocalTime.of(hour, minute);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int parseChineseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        return switch (value) {
            case "零" -> 0;
            case "一" -> 1;
            case "二", "两" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            case "十三" -> 13;
            case "十四" -> 14;
            case "十五" -> 15;
            case "十六" -> 16;
            case "十七" -> 17;
            case "十八" -> 18;
            case "十九" -> 19;
            case "二十" -> 20;
            case "二十一" -> 21;
            case "二十二" -> 22;
            case "二十三" -> 23;
            default -> -1;
        };
    }

    private static boolean hasDateExpression(String value) {
        return value.contains("今天") || value.contains("今日") || value.contains("明天") || value.contains("明日")
                || value.contains("后天") || value.contains("天后") || value.contains("周") || value.contains("星期")
                || value.contains("月") || value.matches("\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }
}
