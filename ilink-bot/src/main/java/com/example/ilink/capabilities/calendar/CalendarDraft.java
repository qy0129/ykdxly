package com.example.ilink.capabilities.calendar;

/** 大模型和多轮补充共同填写的日历草稿，最终时间由 CalendarTimeResolver 统一计算。 */
public record CalendarDraft(
        String title,
        String type,
        String recurrence,
        String timeExpression,
        String timeType,
        long timeAmount,
        String timeUnit,
        int leadTimeSeconds) {

    public CalendarDraft {
        title = title == null ? "" : title.trim();
        type = type == null || type.isBlank() ? "生活" : type;
        recurrence = recurrence == null || recurrence.isBlank() ? "none" : recurrence;
        timeExpression = timeExpression == null ? "" : timeExpression.trim();
        timeType = timeType == null || timeType.isBlank() ? "auto" : timeType;
        timeUnit = timeUnit == null ? "" : timeUnit;
        timeAmount = Math.max(0, timeAmount);
        leadTimeSeconds = Math.max(0, leadTimeSeconds);
    }

    public CalendarDraft withTime(String expression, String type, long amount, String unit, int leadSeconds) {
        return new CalendarDraft(title, this.type, recurrence, expression, type, amount, unit, leadSeconds);
    }
}
