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
    private static final String NUMBER = "[零〇一二三四五六七八九十百千两\\d]+";
    private static final Pattern DAYS_LATER_PATTERN = Pattern.compile("(" + NUMBER + ")天后");
    private static final Pattern RELATIVE_AFTER_PATTERN = Pattern.compile(
            "(?:过|再过)(" + NUMBER + ")(秒钟?|分钟?|分|个?小时|钟头|天)|"
                    + "(" + NUMBER + ")(秒钟?|分钟?|分|个?小时|钟头|天)后");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?:(\\d{4})年)?(\\d{1,2})月(\\d{1,2})(?:日|号)?");
    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern CLOCK_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2}))?");
    private static final Pattern HOUR_PATTERN = Pattern.compile(
            "(" + NUMBER + ")点(?:(半)|(" + NUMBER + ")分?)?(?:(" + NUMBER + ")秒)?");

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
    public static LocalDateTime parse(String expression, LocalDateTime referenceTime) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        String value = normalize(expression);
        LocalDateTime relative = parseRelative(value, referenceTime);
        if (relative != null) return relative;
        LocalDateTime standard = parseStandard(value);
        if (standard != null) {
            return standard;
        }

        LocalDate date = parseDate(value, referenceTime.toLocalDate());
        LocalTime time = parseTime(value);
        if (time == null && isCurrentTimeExpression(value)) {
            time = referenceTime.toLocalTime().withNano(0);
        }
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
        String value = expression.replaceAll("\\s+", "")
                .replace('：', ':')
                .replaceAll("(?<=\\d)[.。·](?=\\d)", ":")
                .replace("之后", "后")
                .replace("以后", "后")
                .replace("过后", "后")
                .replace("半个小时", "30分钟")
                .replace("半小时", "30分钟")
                .replace("半钟头", "30分钟")
                .trim();
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
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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

        Matcher isoDate = ISO_DATE_PATTERN.matcher(value);
        if (isoDate.find()) {
            try {
                return LocalDate.of(Integer.parseInt(isoDate.group(1)),
                        Integer.parseInt(isoDate.group(2)), Integer.parseInt(isoDate.group(3)));
            } catch (RuntimeException ignored) {
                return null;
            }
        }

        Matcher daysLater = DAYS_LATER_PATTERN.matcher(value);
        if (daysLater.find()) {
            int days = parseChineseNumber(daysLater.group(1));
            return days < 0 ? null : today.plusDays(days);
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
            int second = clock.group(3) == null ? 0 : Integer.parseInt(clock.group(3));
            return createTime(Integer.parseInt(clock.group(1)), Integer.parseInt(clock.group(2)), second, value);
        }

        Matcher hour = HOUR_PATTERN.matcher(value);
        if (!hour.find()) {
            return null;
        }
        int hourValue = parseChineseNumber(hour.group(1));
        int minute = hour.group(2) != null ? 30
                : hour.group(3) == null ? 0 : parseChineseNumber(hour.group(3));
        int second = hour.group(4) == null ? 0 : parseChineseNumber(hour.group(4));
        return hourValue < 0 || minute < 0 || second < 0
                ? null : createTime(hourValue, minute, second, value);
    }

    private static LocalTime createTime(int hour, int minute, int second, String value) {
        if (value.contains("下午") || value.contains("傍晚") || value.contains("晚上") || value.contains("今晚")) {
            if (hour > 0 && hour < 12) hour += 12;
        }
        if (value.contains("凌晨") && hour == 12) hour = 0;
        try {
            return LocalTime.of(hour, minute, second);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public static int parseChineseNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
        }
        if (value == null || value.isBlank()) return -1;
        int result = 0;
        int number = 0;
        for (char character : value.replace('两', '二').toCharArray()) {
            int digit = chineseDigit(character);
            if (digit >= 0) {
                number = digit;
                continue;
            }
            int unit = switch (character) {
                case '十' -> 10;
                case '百' -> 100;
                case '千' -> 1000;
                default -> -1;
            };
            if (unit < 0) return -1;
            result += (number == 0 ? 1 : number) * unit;
            number = 0;
        }
        return result + number;
    }

    /** 日历解析用于判断用户是否明确给出了时刻，而不是只有日期。 */
    public static boolean hasExplicitTime(String expression) {
        if (expression == null || expression.isBlank()) return false;
        String value = normalize(expression);
        return RELATIVE_AFTER_PATTERN.matcher(value).find() || CLOCK_PATTERN.matcher(value).find()
                || HOUR_PATTERN.matcher(value).find() || value.contains("下班")
                || isCurrentTimeExpression(value);
    }

    /** 判断原话是否真的包含可用于提醒的时刻证据，防止模型凭空补出默认时间。 */
    public static boolean hasTimeEvidence(String expression) {
        if (expression == null || expression.isBlank()) return false;
        String value = normalize(expression);
        return hasExplicitTime(value)
                || value.matches(".*(?:过|再过)?" + NUMBER + "(?:秒钟?|分钟?|分|个?小时|钟头|天)后.*")
                || value.matches(".*\\d{1,2}:\\d{1,2}(?::\\d{1,2})?.*");
    }

    private static LocalDateTime parseRelative(String value, LocalDateTime referenceTime) {
        if (value.contains("提前")) return null;
        Matcher matcher = RELATIVE_AFTER_PATTERN.matcher(value);
        if (!matcher.find()) return null;
        String numberText = matcher.group(1) == null ? matcher.group(3) : matcher.group(1);
        String unit = matcher.group(2) == null ? matcher.group(4) : matcher.group(2);
        int amount = parseChineseNumber(numberText);
        if (amount < 0) return null;
        if (unit.startsWith("秒")) return referenceTime.plusSeconds(amount);
        if (unit.startsWith("分")) return referenceTime.plusMinutes(amount);
        if (unit.contains("小时") || unit.contains("钟头")) return referenceTime.plusHours(amount);
        return referenceTime.plusDays(amount);
    }

    private static int chineseDigit(char character) {
        return switch (character) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> -1;
        };
    }

    private static boolean isCurrentTimeExpression(String value) {
        return value.contains("这个时候") || value.contains("这个时间")
                || value.contains("当前时间") || value.contains("现在这个时间");
    }

    private static boolean hasDateExpression(String value) {
        return value.contains("今天") || value.contains("今日") || value.contains("明天") || value.contains("明日")
                || value.contains("后天") || value.contains("天后") || value.contains("周") || value.contains("星期")
                || value.contains("月") || value.matches(".*\\d{4}-\\d{1,2}-\\d{1,2}.*");
    }
}
