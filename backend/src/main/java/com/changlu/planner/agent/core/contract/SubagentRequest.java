package com.changlu.planner.agent.core.contract;

import com.google.gson.JsonObject;
import java.util.List;

public record SubagentRequest(String message, JsonObject arguments, List<String> documentIds) {
  public SubagentRequest {
    message = message == null ? "" : message.trim();
    arguments = arguments == null ? new JsonObject() : arguments.deepCopy();
    documentIds = documentIds == null ? List.of() : List.copyOf(documentIds);
  }
}
