package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.travel.tools.support.AmapClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** Estimates bounded route segments; it never books transportation. */
public final class RouteEstimateTool implements ToolHandler {
  public static final String NAME = "travel.route.estimate";
  private final AmapClient amap;

  public RouteEstimateTool() { this(new AmapClient()); }

  public RouteEstimateTool(AmapClient amap) { this.amap = amap; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"segments":{"type":"array","minItems":1,"maxItems":16,
        "items":{"type":"object","properties":{"origin":{"type":"string","minLength":1},
        "destination":{"type":"string","minLength":1},"city":{"type":"string"},"date":{"type":"string"}},
        "required":["origin","destination"]}}},"required":["segments"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString(
        "{\"type\":\"object\",\"properties\":{\"routes\":{\"type\":\"array\"}},\"required\":[\"routes\"]}")
        .getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "估算旅行地点之间的距离和驾车耗时", input, output,
        Set.of("travel:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false,
        Duration.ofSeconds(30), RetryPolicy.readOnlyNetwork());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    JsonArray segments = array(call.arguments(), "segments");
    if (segments.isEmpty()) throw new IllegalArgumentException("TRAVEL_ROUTE_SEGMENTS_REQUIRED");
    JsonArray routes = new JsonArray();
    for (JsonElement value : segments) {
      if (!value.isJsonObject()) throw new IllegalArgumentException("TRAVEL_ROUTE_SEGMENT_INVALID");
      JsonObject segment = value.getAsJsonObject();
      String origin = text(segment, "origin").trim();
      String destination = text(segment, "destination").trim();
      if (origin.isBlank() || destination.isBlank()) {
        throw new IllegalArgumentException("TRAVEL_ROUTE_ENDPOINT_REQUIRED");
      }
      JsonObject route = new JsonObject();
      route.addProperty("origin", origin);
      route.addProperty("destination", destination);
      copyIfPresent(segment, route, "city");
      copyIfPresent(segment, route, "date");
      try {
        JsonObject estimate = amap.route(origin, destination, text(segment, "city"));
        for (String key : estimate.keySet()) route.add(key, estimate.get(key).deepCopy());
        route.addProperty("status", "estimated");
      } catch (Exception error) {
        route.addProperty("status", "unavailable");
        route.addProperty("verificationRequired", true);
        route.addProperty("errorCode", error.getMessage() == null ? "ROUTE_UNAVAILABLE" : error.getMessage());
      }
      route.addProperty("queriedAt", Instant.now().toString());
      routes.add(route);
    }
    JsonObject data = new JsonObject();
    data.add("routes", routes);
    return AgentResult.completed("已完成路线耗时估算，具体交通方式仍需出发前核实", data, context.traceId());
  }

  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray()
        ? object.getAsJsonArray(name) : new JsonArray();
  }

  private String text(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
  }

  private void copyIfPresent(JsonObject source, JsonObject target, String name) {
    if (source.has(name) && !source.get(name).isJsonNull()) target.add(name, source.get(name).deepCopy());
  }
}
