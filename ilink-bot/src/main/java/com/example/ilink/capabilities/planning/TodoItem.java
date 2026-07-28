package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;

/** 可独立于计划存在、并可关联日历提醒的待办事项。 */
public record TodoItem(
        String id,
        String userId,
        String title,
        LocalDateTime dueAt,
        String status,
        String calendarEventId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public TodoItem withStatus(String newStatus) {
        return new TodoItem(id, userId, title, dueAt, newStatus, calendarEventId, createdAt, LocalDateTime.now());
    }
}
