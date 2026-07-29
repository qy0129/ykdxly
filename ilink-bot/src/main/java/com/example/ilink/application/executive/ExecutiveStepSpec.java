package com.example.ilink.application.executive;

import com.google.gson.JsonObject;

import java.util.List;

/** Planner 生成步骤时使用的输入协议。 */
public record ExecutiveStepSpec(String title, String capability, String toolName,
                                JsonObject arguments, List<Integer> dependsOn,
                                boolean requiresApproval, RiskLevel riskLevel,
                                int maxAttempts, String verificationRule) {
    public ExecutiveStepSpec {
        arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        riskLevel = riskLevel == null ? RiskLevel.READ_ONLY : riskLevel;
        maxAttempts = Math.max(1, maxAttempts);
        verificationRule = verificationRule == null ? "non_empty" : verificationRule;
    }
}
