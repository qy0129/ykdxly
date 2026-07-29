package com.example.ilink.application.executive;

import java.time.LocalDateTime;

/** 不可覆盖的任务执行时间线记录。 */
public record ExecutionLog(String id, String taskId, String stepId, String userId,
                           String eventType, String status, String message,
                           String payloadJson, LocalDateTime createdAt) {
    public ExecutionLog {
        stepId = stepId == null ? "" : stepId;
        eventType = eventType == null ? "STATUS" : eventType;
        status = status == null ? "" : status;
        message = message == null ? "" : message;
        payloadJson = payloadJson == null ? "{}" : payloadJson;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }
}
