package com.example.ilink.capabilities.planning;

import java.util.Objects;

/**
 * 规划中的单个可执行任务。
 *
 * @param id 任务唯一标识
 * @param title 任务标题
 * @param description 任务说明
 * @param estimatedMinutes 预计耗时，单位为分钟
 * @param priority 优先级：high、medium 或 low
 * @param scheduledDate 安排日期，格式为 yyyy-MM-dd
 * @param status 状态：pending 或 completed
 */
public record PlanTask(
        String id,
        String title,
        String description,
        int estimatedMinutes,
        String priority,
        String scheduledDate,
        String status) {

    /** 校验任务必填字段，并把空说明、日期和状态转换为稳定默认值。 */
    public PlanTask {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        description = description == null ? "" : description;
        estimatedMinutes = Math.max(15, estimatedMinutes);
        priority = priority == null || priority.isBlank() ? "medium" : priority;
        scheduledDate = scheduledDate == null ? "" : scheduledDate;
        status = status == null || status.isBlank() ? "pending" : status;
    }

    /** 返回安排到指定日期的新任务对象。 */
    public PlanTask scheduleOn(String date) {
        return new PlanTask(id, title, description, estimatedMinutes,
                priority, date, status);
    }

    /** 返回修改完成状态后的新任务对象。 */
    public PlanTask withStatus(String newStatus) {
        return new PlanTask(id, title, description, estimatedMinutes,
                priority, scheduledDate, newStatus);
    }

    public PlanTask withDescription(String newDescription) {
        return new PlanTask(id, title, newDescription, estimatedMinutes,
                priority, scheduledDate, status);
    }
}
