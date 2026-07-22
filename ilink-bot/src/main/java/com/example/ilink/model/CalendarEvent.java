package com.example.ilink.model;

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
        int reminderMinutes,
        String status,
        String notes,
        LocalDateTime createdAt) {

    public CalendarEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(title, "title");
        type = type == null || type.isBlank() ? "其他" : type;
        Objects.requireNonNull(startAt, "startAt");
        recurrence = recurrence == null || recurrence.isBlank() ? "none" : recurrence;
        status = status == null || status.isBlank() ? "active" : status;
        notes = notes == null ? "" : notes;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    /** 返回更新下一次提醒时间后的事件副本。 */
    public CalendarEvent withNextReminderAt(LocalDateTime value) {
        return new CalendarEvent(id, userId, title, type, startAt, value, recurrence,
                reminderMinutes, status, notes, createdAt);
    }

    /** 返回更新状态后的事件副本。 */
    public CalendarEvent withStatus(String value) {
        return new CalendarEvent(id, userId, title, type, startAt, nextReminderAt, recurrence,
                reminderMinutes, value, notes, createdAt);
    }
}
