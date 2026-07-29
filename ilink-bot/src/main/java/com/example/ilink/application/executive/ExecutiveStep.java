package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 通用任务中的一个可执行步骤。 */
public record ExecutiveStep(
        String id,
        String taskId,
        int sequence,
        String title,
        String capability,
        String toolName,
        String inputJson,
        String outputText,
        StepStatus status,
        List<String> dependsOn,
        boolean requiresApproval,
        RiskLevel riskLevel,
        int attempts,
        int maxAttempts,
        LocalDateTime nextRunAt,
        String verificationRule,
        String lastError,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {

    public ExecutiveStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(taskId, "taskId");
        title = title == null || title.isBlank() ? capability : title.trim();
        capability = capability == null ? "" : capability.trim();
        toolName = toolName == null ? "" : toolName.trim();
        inputJson = inputJson == null || inputJson.isBlank() ? "{}" : inputJson;
        outputText = outputText == null ? "" : outputText;
        status = status == null ? StepStatus.PENDING : status;
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        riskLevel = riskLevel == null ? RiskLevel.READ_ONLY : riskLevel;
        maxAttempts = Math.max(1, maxAttempts);
        verificationRule = verificationRule == null ? "non_empty" : verificationRule;
        lastError = lastError == null ? "" : lastError;
    }

    public boolean approvalRequired() {
        return requiresApproval || riskLevel.requiresApproval();
    }

    public ExecutiveStep withStatus(StepStatus newStatus, int newAttempts, LocalDateTime runAt,
                                    String output, String error, LocalDateTime started,
                                    LocalDateTime finished) {
        return new ExecutiveStep(id, taskId, sequence, title, capability, toolName,
                inputJson, output, newStatus, dependsOn, requiresApproval, riskLevel,
                newAttempts, maxAttempts, runAt, verificationRule, error, started, finished);
    }
}
