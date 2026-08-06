package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.*;
import com.changlu.planner.agent.subagents.travel.services.GeocodingService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.Set;

public final class LocationContextTool implements ToolHandler {
  public static final String NAME = "travel.location.context";
  private final GeocodingService service;
  public LocationContextTool(GeocodingService service) { this.service = service; }
  @Override public ToolDefinition definition() { return definition(NAME, "解析旅行出发地、目的地和 GCJ-02 坐标", 15); }
  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    JsonObject data = new JsonObject(); data.add("locationContext", service.resolve(call.arguments(), context.traceId()));
    return AgentResult.completed("已解析旅行位置上下文。", data, context.traceId());
  }
  static ToolDefinition definition(String name, String description, int timeout) {
    JsonObject input = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
    JsonObject output = JsonParser.parseString("{\"type\":\"object\"}").getAsJsonObject();
    return new ToolDefinition(name, "1.0.0", description, input, output, Set.of("travel:read"),
        ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false, Duration.ofSeconds(timeout), RetryPolicy.readOnlyNetwork());
  }
}
