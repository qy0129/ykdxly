package com.example.ilink.capabilities.automation;

import com.example.ilink.application.executive.ScheduleRule;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record AutomationSchedule(ScheduleRule rule, LocalDateTime nextRunAt) {
    private static final Pattern WEEKLY = Pattern.compile(
            "\\u6bcf\\s*\\u5468\\s*([\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u65e5\\u5929])");
    private static final Pattern CLOCK = Pattern.compile(
            "(?:\\u65e9\\u4e0a|\\u4e0a\\u5348|\\u4e2d\\u5348|\\u4e0b\\u5348|\\u665a\\u4e0a)?\\s*"
                    + "(\\d{1,2})(?:[:\\uff1a](\\d{1,2}))?\\s*(?:\\u70b9|\\u65f6)?");
    private static final Pattern SCHEDULE_PREFIX = Pattern.compile(
            "^\\s*(?:(?:\\u6bcf\\s*\\u5468\\s*[\\u4e00\\u4e8c\\u4e09\\u56db\\u4e94\\u516d\\u65e5\\u5929])"
                    + "|(?:\\u6bcf\\s*\\u5929))"
                    + "\\s*(?:\\u65e9\\u4e0a|\\u4e0a\\u5348|\\u4e2d\\u5348|\\u4e0b\\u5348|\\u665a\\u4e0a)?"
                    + "\\s*(?:\\d{1,2}(?:[:\\uff1a]\\d{1,2})?\\s*(?:\\u70b9|\\u65f6)?)?\\s*");

    public AutomationSchedule {
        rule = rule == null ? ScheduleRule.NONE : rule;
        nextRunAt = nextRunAt == null ? LocalDateTime.now() : nextRunAt;
    }

    public static AutomationSchedule parse(String text, LocalDateTime now) {
        String value = text == null ? "" : text;
        LocalTime time = parseTime(value);
        Matcher weekly = WEEKLY.matcher(value);
        if (weekly.find()) {
            DayOfWeek day = dayOfWeek(weekly.group(1).charAt(0));
            LocalDate date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(day));
            LocalDateTime candidate = LocalDateTime.of(date, time);
            if (!candidate.isAfter(now)) candidate = candidate.plusWeeks(1);
            return new AutomationSchedule(ScheduleRule.WEEKLY, candidate);
        }
        if (value.matches("(?s).*\\u6bcf\\s*\\u5929.*")) {
            LocalDateTime candidate = LocalDateTime.of(now.toLocalDate(), time);
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
            return new AutomationSchedule(ScheduleRule.DAILY, candidate);
        }
        return new AutomationSchedule(ScheduleRule.NONE, now);
    }

    public static String stripPrefix(String text) {
        return SCHEDULE_PREFIX.matcher(text == null ? "" : text).replaceFirst("").trim();
    }

    private static LocalTime parseTime(String value) {
        Matcher clock = CLOCK.matcher(value);
        while (clock.find()) {
            String matched = clock.group();
            if (!containsTimeMarker(matched)) continue;
            int hour = Integer.parseInt(clock.group(1));
            int minute = clock.group(2) == null ? 0 : Integer.parseInt(clock.group(2));
            if ((matched.contains("\u4e0b\u5348") || matched.contains("\u665a\u4e0a")) && hour < 12) {
                hour += 12;
            }
            if (matched.contains("\u4e2d\u5348") && hour < 11) hour += 12;
            if (hour < 24 && minute < 60) return LocalTime.of(hour, minute);
        }
        return LocalTime.of(9, 0);
    }

    private static boolean containsTimeMarker(String value) {
        return value.contains(":") || value.contains("\uff1a") || value.contains("\u70b9")
                || value.contains("\u65f6") || value.contains("\u65e9\u4e0a")
                || value.contains("\u4e0a\u5348") || value.contains("\u4e2d\u5348")
                || value.contains("\u4e0b\u5348") || value.contains("\u665a\u4e0a");
    }

    private static DayOfWeek dayOfWeek(char value) {
        return switch (value) {
            case '\u4e8c' -> DayOfWeek.TUESDAY;
            case '\u4e09' -> DayOfWeek.WEDNESDAY;
            case '\u56db' -> DayOfWeek.THURSDAY;
            case '\u4e94' -> DayOfWeek.FRIDAY;
            case '\u516d' -> DayOfWeek.SATURDAY;
            case '\u65e5', '\u5929' -> DayOfWeek.SUNDAY;
            default -> DayOfWeek.MONDAY;
        };
    }
}
