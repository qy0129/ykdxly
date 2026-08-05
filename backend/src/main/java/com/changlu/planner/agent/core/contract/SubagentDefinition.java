package com.changlu.planner.agent.core.contract;

import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;

public record SubagentDefinition(
    String name,
    String version,
    String description,
    List<String> supportedScenarios,
    List<String> unsupportedScenarios,
    JsonObject inputSchema,
    JsonObject outputSchema,
    Set<String> allowedTools,
    boolean networkAllowed,
    boolean writeAllowed,
    Duration timeout,
    int maxIterations
) {
  public SubagentDefinition {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("subagent_name_required");
    version = version == null || version.isBlank() ? "1.0.0" : version;
    description = description == null ? "" : description;
    supportedScenarios = supportedScenarios == null ? List.of() : List.copyOf(supportedScenarios);
    unsupportedScenarios = unsupportedScenarios == null ? List.of() : List.copyOf(unsupportedScenarios);
    inputSchema = inputSchema == null ? new JsonObject() : inputSchema.deepCopy();
    outputSchema = outputSchema == null ? new JsonObject() : outputSchema.deepCopy();
    allowedTools = allowedTools == null ? Set.of() : Set.copyOf(allowedTools);
    timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
    if (maxIterations < 1) throw new IllegalArgumentException("subagent_max_iterations_invalid");
  }
}
