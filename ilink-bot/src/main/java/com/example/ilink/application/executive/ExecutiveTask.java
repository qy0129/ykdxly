package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.Objects;

/** 可持久化、可恢复的通用执行任务。 */
public record ExecutiveTask(
        String id,
        String userId,
        String goal,
        String sourceType,
        String sourceId,
        String dedupKey,
        TaskStatus status,
        String priority,
        LocalDateTime deadlineAt,
        LocalDateTime nextRunAt,
        ScheduleRule scheduleRule,
        int currentStep,
        int planVersion,
        int retryCount,
        int maxRetries,
        String lastError,
        String lockOwner,
        LocalDateTime lockedUntil,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public ExecutiveTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        goal = goal == null ? "" : goal.trim();
        sourceType = sourceType == null ? "wechat" : sourceType.trim();
        sourceId = sourceId == null ? "" : sourceId.trim();
        dedupKey = dedupKey == null ? id : dedupKey.trim();
        status = status == null ? TaskStatus.CREATED : status;
        priority = priority == null || priority.isBlank() ? "medium" : priority.trim();
        scheduleRule = scheduleRule == null ? ScheduleRule.NONE : scheduleRule;
        maxRetries = Math.max(0, maxRetries);
        lastError = lastError == null ? "" : lastError;
        lockOwner = lockOwner == null ? "" : lockOwner;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    public ExecutiveTask withState(TaskStatus newStatus, LocalDateTime runAt, String error) {
        return new ExecutiveTask(id, userId, goal, sourceType, sourceId, dedupKey,
                newStatus, priority, deadlineAt, runAt, scheduleRule, currentStep,
                planVersion, retryCount, maxRetries, error, "", null, createdAt, LocalDateTime.now());
    }

    public ExecutiveTask withProgress(TaskStatus newStatus, int step, LocalDateTime runAt,
                                      int retries, String error) {
        return new ExecutiveTask(id, userId, goal, sourceType, sourceId, dedupKey,
                newStatus, priority, deadlineAt, runAt, scheduleRule, step,
                planVersion, retries, maxRetries, error, "", null, createdAt, LocalDateTime.now());
    }

    public ExecutiveTask claimed(String owner, LocalDateTime until) {
        return new ExecutiveTask(id, userId, goal, sourceType, sourceId, dedupKey,
                status, priority, deadlineAt, nextRunAt, scheduleRule, currentStep,
                planVersion, retryCount, maxRetries, lastError, owner, until, createdAt, LocalDateTime.now());
    }

    public ExecutiveTask withDeadline(LocalDateTime value) {
        return new ExecutiveTask(id, userId, goal, sourceType, sourceId, dedupKey,
                status, priority, value, nextRunAt, scheduleRule, currentStep,
                planVersion, retryCount, maxRetries, lastError, lockOwner, lockedUntil,
                createdAt, LocalDateTime.now());
    }
}
