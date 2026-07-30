package com.example.ilink.capabilities.life;

/** 用户对计划任务的一次执行反馈。 */
public record TaskActivity(
        String id,
        String planId,
        String taskId,
        String type,
        String detail,
        String occurredAt) {
}
