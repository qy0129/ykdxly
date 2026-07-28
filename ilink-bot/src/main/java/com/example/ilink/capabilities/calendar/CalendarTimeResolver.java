package com.example.ilink.capabilities.calendar;

import com.example.ilink.capabilities.planning.DateTimeParser;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将结构化草稿和用户原话统一解析为事件时间与实际提醒时间。 */
public final class CalendarTimeResolver {

    private static final String NUMBER = "[零〇一二三四五六七八九十百千两\\d]+";
    private static final Pattern LEAD_TIME_PATTERN = Pattern.compile(
            "提前(" + NUMBER + ")(秒钟?|分钟?|分|个?小时|钟头|天)");
    private static final Pattern MONTHLY_DAY_PATTERN = Pattern.compile(
            "每月\\s*(" + NUMBER + ")\\s*(?:号|日)");

    public ResolvedCalendarTime resolve(CalendarDraft draft, String rawText, LocalDateTime now) {
        String original = rawText == null ? "" : rawText.trim();
        LocalDateTime eventAt = parseCandidate(original, draft.recurrence(), now);
        String selectedExpression = original;
        if (eventAt == null) {
            selectedExpression = draft.timeExpression();
            eventAt = parseCandidate(selectedExpression, draft.recurrence(), now);
        }
        if (eventAt == null && "relative".equals(draft.timeType()) && draft.timeAmount() > 0) {
            eventAt = add(now, draft.timeAmount(), draft.timeUnit());
            selectedExpression = draft.timeAmount() + draft.timeUnit() + "后";
        }
        if (eventAt == null) return ResolvedCalendarTime.unresolved("missing_time");

        boolean relative = isRelative(original) || "relative".equals(draft.timeType());
        if (!relative && !DateTimeParser.hasExplicitTime(selectedExpression)) {
            return ResolvedCalendarTime.unresolved("missing_clock");
        }

        int leadSeconds = parseLeadTimeSeconds(original);
        if (leadSeconds == 0) leadSeconds = draft.leadTimeSeconds();
        LocalDateTime remindAt = eventAt.minusSeconds(leadSeconds);
        if (!remindAt.isAfter(now)) return ResolvedCalendarTime.unresolved("not_future");

        String precision = selectedExpression.contains("秒") || eventAt.getSecond() != 0 || remindAt.getSecond() != 0
                ? "second" : "minute";
        return new ResolvedCalendarTime(true, eventAt, remindAt, leadSeconds, precision, relative, "");
    }

    public int parseLeadTimeSeconds(String text) {
        if (text == null) return 0;
        Matcher matcher = LEAD_TIME_PATTERN.matcher(text.replaceAll("\\s+", ""));
        if (!matcher.find()) return 0;
        int amount = DateTimeParser.parseChineseNumber(matcher.group(1));
        if (amount < 0) return 0;
        return Math.toIntExact(toSeconds(amount, matcher.group(2)));
    }

    private LocalDateTime parseCandidate(String expression, String recurrence, LocalDateTime now) {
        if (expression == null || expression.isBlank()) return null;
        if ("monthly".equals(recurrence) || expression.contains("每月")) {
            LocalDateTime monthly = parseMonthly(expression, now);
            if (monthly != null) return monthly;
        }
        return DateTimeParser.parse(expression, now);
    }

    private LocalDateTime parseMonthly(String expression, LocalDateTime now) {
        Matcher matcher = MONTHLY_DAY_PATTERN.matcher(expression);
        if (!matcher.find() || !DateTimeParser.hasExplicitTime(expression)) return null;
        int day = DateTimeParser.parseChineseNumber(matcher.group(1));
        LocalDateTime parsed = DateTimeParser.parse(expression, now);
        LocalTime time = parsed == null ? null : parsed.toLocalTime();
        if (day <= 0 || time == null) return null;
        YearMonth month = YearMonth.from(now);
        for (int offset = 0; offset < 13; offset++) {
            YearMonth candidate = month.plusMonths(offset);
            if (day <= candidate.lengthOfMonth()) {
                LocalDateTime value = candidate.atDay(day).atTime(time);
                if (value.isAfter(now)) return value;
            }
        }
        return null;
    }

    private LocalDateTime add(LocalDateTime now, long amount, String unit) {
        long seconds = toSeconds(amount, unit);
        return seconds <= 0 ? null : now.plus(seconds, ChronoUnit.SECONDS);
    }

    private long toSeconds(long amount, String unit) {
        String value = unit == null ? "" : unit.toLowerCase();
        if (value.startsWith("second") || value.startsWith("秒")) return amount;
        if (value.startsWith("minute") || value.startsWith("分")) return amount * 60;
        if (value.startsWith("hour") || value.contains("小时") || value.contains("钟头")) return amount * 3600;
        if (value.startsWith("day") || value.startsWith("天")) return amount * 86400;
        return 0;
    }

    private boolean isRelative(String text) {
        if (text == null) return false;
        String value = text.replaceAll("\\s+", "");
        return value.matches(".*((过|再过)" + NUMBER + "(秒钟?|分钟?|分|个?小时|钟头|天)|"
                + NUMBER + "(秒钟?|分钟?|分|个?小时|钟头|天)后).*" );
    }

    public record ResolvedCalendarTime(
            boolean resolved,
            LocalDateTime eventAt,
            LocalDateTime remindAt,
            int leadTimeSeconds,
            String precision,
            boolean relative,
            String error) {

        static ResolvedCalendarTime unresolved(String error) {
            return new ResolvedCalendarTime(false, null, null, 0, "", false, error);
        }
    }
}
