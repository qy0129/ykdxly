package com.example.ilink.application.executive;

import java.time.LocalDateTime;

/** 高风险步骤的持久化审批请求。 */
public record ApprovalRequest(String id, String taskId, String stepId, String userId,
                              RiskLevel riskLevel, String actionSummary, String status,
                              LocalDateTime expiresAt, LocalDateTime actedAt,
                              LocalDateTime createdAt) {
    public ApprovalRequest {
        riskLevel = riskLevel == null ? RiskLevel.EXTERNAL_WRITE : riskLevel;
        actionSummary = actionSummary == null ? "" : actionSummary;
        status = status == null ? "PENDING" : status;
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public boolean pending() {
        return "PENDING".equals(status) && !expired();
    }

    public boolean expired() {
        return expiresAt != null && !expiresAt.isAfter(LocalDateTime.now());
    }
}
