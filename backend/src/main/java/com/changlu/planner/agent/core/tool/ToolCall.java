package com.changlu.planner.agent.core.tool;

import com.google.gson.JsonObject;

public record ToolCall(String toolCallId, String idempotencyKey, String toolName, JsonObject arguments) {
  public ToolCall {
    if (toolCallId == null || toolCallId.isBlank()) throw new IllegalArgumentException("tool_call_id_required");
    if (toolName == null || toolName.isBlank()) throw new IllegalArgumentException("tool_name_required");
    arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
  }
}
