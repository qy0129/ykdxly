package com.changlu.planner.agent.subagents.briefing;

import com.google.gson.JsonObject;

/** 每日简报的结构化结果。 */
public record BriefingResult(
    String message,
    int plans,
    long progress,
    int pendingTodos,
    int overdueTodos,
    String tone
) {
  public JsonObject toAgentJson() {
    JsonObject result = new JsonObject();
    result.addProperty("reply", message);
    JsonObject data = new JsonObject();
    data.addProperty("plans", plans);
    data.addProperty("progress", progress);
    data.addProperty("pendingTodos", pendingTodos);
    data.addProperty("overdueTodos", overdueTodos);
    data.addProperty("tone", tone);
    result.add("briefing", data);
    return result;
  }
}
