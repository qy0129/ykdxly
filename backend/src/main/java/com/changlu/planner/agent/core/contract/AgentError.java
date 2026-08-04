package com.changlu.planner.agent.core.contract;

import com.google.gson.JsonObject;

public record AgentError(String code, String message, boolean retryable, JsonObject details) {
  public AgentError {
    if (code == null || code.isBlank()) throw new IllegalArgumentException("error_code_required");
    if (message == null || message.isBlank()) throw new IllegalArgumentException("error_message_required");
    details = details == null ? new JsonObject() : details.deepCopy();
  }
}
