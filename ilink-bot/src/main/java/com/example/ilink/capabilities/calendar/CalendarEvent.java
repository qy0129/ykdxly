package com.example.ilink.capabilities.calendar;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * 日历中的一个可提醒事件。周期事件只保存下一次触发时间，避免为每次重复预生成记录。
 */
public record CalendarEvent(
        String id,
        String userId,
        String title,
        String type,
        LocalDateTime startAt,
        LocalDateTime nextReminderAt,
        String recurrence,
        String recurrenceAnchor,
        int reminderMinutes,
        String status,
        String groupId,
        String source,
        String notes,
        LocalDateTime createdAt) {

    public CalendarEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(title, "title");
        type = type == null || type.isBlank() ? "其他" : type;
        Objects.requireNonNull(startAt, "startAt");
        recurrence = recurrence == null || recurrence.isBlank() ? "none" : recurrence;
        recurrenceAnchor = recurrenceAnchor == null ? "" : recurrenceAnchor;
        status = status == null || status.isBlank() ? "active" : status;
        groupId = groupId == null ? "" : groupId;
        source = source == null ? "" : source;
        notes = notes == null ? "" : notes;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /** 返回更新下一次提醒时间后的事件副本。 */
    public CalendarEvent withNextReminderAt(LocalDateTime value) {
        return new CalendarEvent(id, userId, title, type, startAt, value, recurrence, recurrenceAnchor,
                reminderMinutes, status, groupId, source, notes, createdAt);
    }

    /** 返回更新状态后的事件副本。 */
    public CalendarEvent withStatus(String value) {
        return new CalendarEvent(id, userId, title, type, startAt, nextReminderAt, recurrence, recurrenceAnchor,
                reminderMinutes, value, groupId, source, notes, createdAt);
    }

    /** 周期提醒发送后，同时推进事件时间和提醒时间。 */
    public CalendarEvent withSchedule(LocalDateTime newStartAt, LocalDateTime newReminderAt) {
        return new CalendarEvent(id, userId, title, type, newStartAt, newReminderAt, recurrence, recurrenceAnchor,
                reminderMinutes, status, groupId, source, notes, createdAt);
    }

    public CalendarEvent withDetails(String newTitle, LocalDateTime newStartAt, LocalDateTime newReminderAt) {
        return new CalendarEvent(id, userId, newTitle, type, newStartAt, newReminderAt, recurrence,
                recurrenceAnchor, reminderMinutes, status, groupId, source, notes, createdAt);
    }
}
