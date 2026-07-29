package com.example.ilink.application.executive;

import java.time.LocalDateTime;

/** 微信离线时仍可保留的主动通知。 */
public record OutboxMessage(String id, String taskId, String userId, String type,
                            String content, String status, int attempts,
                            LocalDateTime availableAt, LocalDateTime sentAt,
                            LocalDateTime createdAt) {
    public OutboxMessage {
        taskId = taskId == null ? "" : taskId;
        type = type == null ? "TASK_STATUS" : type;
        content = content == null ? "" : content;
        status = status == null ? "PENDING" : status;
        availableAt = availableAt == null ? LocalDateTime.now() : availableAt;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }
}
