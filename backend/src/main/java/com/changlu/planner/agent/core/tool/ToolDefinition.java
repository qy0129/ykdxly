package com.changlu.planner.agent.core.tool;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.Set;

public record ToolDefinition(
    String name,
    String version,
    String description,
    JsonObject inputSchema,
    JsonObject outputSchema,
    Set<String> requiredPermissions,
    ToolRiskLevel riskLevel,
    ToolSideEffect sideEffect,
    boolean requiresConfirmation,
    Duration timeout,
    RetryPolicy retryPolicy
) {
  public ToolDefinition {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("tool_name_required");
    version = version == null || version.isBlank() ? "1.0.0" : version;
    description = description == null ? "" : description;
    inputSchema = inputSchema == null ? new JsonObject() : inputSchema.deepCopy();
    outputSchema = outputSchema == null ? new JsonObject() : outputSchema.deepCopy();
    requiredPermissions = requiredPermissions == null ? Set.of() : Set.copyOf(requiredPermissions);
    riskLevel = riskLevel == null ? ToolRiskLevel.READ_ONLY : riskLevel;
    sideEffect = sideEffect == null ? ToolSideEffect.NONE : sideEffect;
    timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
    retryPolicy = retryPolicy == null ? RetryPolicy.none() : retryPolicy;
  }
}
