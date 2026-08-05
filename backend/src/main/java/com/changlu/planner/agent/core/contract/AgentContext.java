package com.changlu.planner.agent.core.contract;

import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record AgentContext(
    UUID runId,
    UUID conversationId,
    String traceId,
    Database.Context identity,
    String channel,
    Set<String> permissions,
    Instant deadline,
    JsonObject taskState
) {
  public AgentContext {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    taskState = taskState == null ? new JsonObject() : taskState.deepCopy();
  }

  public boolean hasPermission(String permission) {
    return permissions.contains("*") || permissions.contains(permission);
  }
}
