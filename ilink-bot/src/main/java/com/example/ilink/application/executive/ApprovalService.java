package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.UUID;

/** 创建并处理高风险步骤审批。 */
public final class ApprovalService {
    private final ExecutiveTaskStore store;

    public ApprovalService(ExecutiveTaskStore store) {
        this.store = store;
    }

    public ApprovalRequest ensurePending(ExecutiveTask task, ExecutiveStep step) {
        ApprovalRequest existing = store.findApprovalByStep(step.id());
        if (existing != null) return existing;
        ApprovalRequest approval = new ApprovalRequest("APR-" + shortId(), task.id(), step.id(), task.userId(),
                step.riskLevel(), step.title(), "PENDING", LocalDateTime.now().plusHours(24),
                null, LocalDateTime.now());
        store.saveApproval(approval);
        return approval;
    }

    public ApprovalRequest decide(String userId, String approvalId, boolean approved) {
        ApprovalRequest current = store.findApproval(approvalId);
        if (current == null || !current.userId().equals(userId) || !current.pending()) return null;
        ApprovalRequest decided = new ApprovalRequest(current.id(), current.taskId(), current.stepId(),
                current.userId(), current.riskLevel(), current.actionSummary(),
                approved ? "APPROVED" : "REJECTED", current.expiresAt(), LocalDateTime.now(), current.createdAt());
        store.saveApproval(decided);
        return decided;
    }

    public ApprovalRequest forStep(String stepId) {
        return store.findApprovalByStep(stepId);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }
}
