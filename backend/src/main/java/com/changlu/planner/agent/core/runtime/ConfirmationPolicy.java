package com.changlu.planner.agent.core.runtime;

import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;

public final class ConfirmationPolicy {
  public boolean requiresConfirmation(ToolDefinition definition) {
    return definition.requiresConfirmation() || definition.riskLevel() == ToolRiskLevel.LOW_RISK_WRITE
        || definition.riskLevel() == ToolRiskLevel.HIGH_RISK_WRITE;
  }

  public boolean denied(ToolDefinition definition) {
    return definition.riskLevel() == ToolRiskLevel.RESTRICTED;
  }
}
