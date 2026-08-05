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
import java.util.LinkedHashSet;
import java.util.Set;

/** Returns forecast data when available and an explicit verification warning otherwise. */
public final class TravelWeatherTool implements ToolHandler {
  public static final String NAME = "travel.weather";
  private final AmapClient amap;

  public TravelWeatherTool() { this(new AmapClient()); }

  public TravelWeatherTool(AmapClient amap) { this.amap = amap; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"locations":{"type":"array","minItems":1,"maxItems":2,
        "items":{"type":"string","minLength":1}},"startDate":{"type":"string"},"endDate":{"type":"string"}},
        "required":["locations"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString("""
        {"type":"object","properties":{"weather":{"type":"object"}},"required":["weather"]}
        """).getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "查询旅行地点天气预报和可用预报范围", input, output,
        Set.of("travel:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false,
        Duration.ofSeconds(20), RetryPolicy.readOnlyNetwork());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    JsonArray locations = array(call.arguments(), "locations");
    if (locations.isEmpty()) throw new IllegalArgumentException("TRAVEL_WEATHER_LOCATIONS_REQUIRED");
    Set<String> unique = new LinkedHashSet<>();
    for (JsonElement value : locations) {
      if (!value.isJsonPrimitive() || value.getAsString().trim().isBlank()) {
        throw new IllegalArgumentException("TRAVEL_WEATHER_LOCATION_INVALID");
      }
      unique.add(value.getAsString().trim());
    }
    JsonObject weather = new JsonObject();
    weather.addProperty("queriedAt", java.time.Instant.now().toString());
    weather.addProperty("provider", "amap");
    weather.addProperty("verificationRequired", true);
    weather.addProperty("startDate", text(call.arguments(), "startDate"));
    weather.addProperty("endDate", text(call.arguments(), "endDate"));
    JsonArray items = new JsonArray();
    JsonArray warnings = new JsonArray();
    for (String location : unique) {
      try {
        items.add(amap.weather(location));
      } catch (Exception error) {
        JsonObject unavailable = new JsonObject();
        unavailable.addProperty("location", location);
        unavailable.addProperty("status", "unavailable");
        unavailable.addProperty("verificationRequired", true);
        unavailable.addProperty("errorCode", error.getMessage() == null ? "WEATHER_UNAVAILABLE" : error.getMessage());
        items.add(unavailable);
        warnings.add(location + "天气暂时不可用，出发前请重新核实");
      }
    }
    weather.add("items", items);
    weather.add("warnings", warnings);
    weather.addProperty("forecastCoversTrip", coversTrip(weather, text(call.arguments(), "startDate"),
        text(call.arguments(), "endDate")));
    if (!weather.get("forecastCoversTrip").getAsBoolean() && !text(call.arguments(), "startDate").isBlank()) {
      warnings.add("旅行日期超出当前天气预报范围，请在出发前重新查询");
    }
    JsonObject data = new JsonObject();
    data.add("weather", weather);
    return AgentResult.completed(warnings.isEmpty() ? "已查询旅行地点天气" : "部分天气数据不可用，请出发前核实",
        data, context.traceId());
  }

  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray()
        ? object.getAsJsonArray(name) : new JsonArray();
  }

  private String text(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
  }

  private boolean coversTrip(JsonObject weather, String startDate, String endDate) {
    if (startDate.isBlank() || endDate.isBlank()) return false;
    try {
      java.time.LocalDate start = java.time.LocalDate.parse(startDate);
      java.time.LocalDate end = java.time.LocalDate.parse(endDate);
      for (JsonElement item : array(weather, "items")) {
        JsonArray forecasts = array(item.getAsJsonObject(), "forecasts");
        if (forecasts.isEmpty()) return false;
        java.time.LocalDate min = null, max = null;
        for (JsonElement forecast : forecasts) {
          java.time.LocalDate date = java.time.LocalDate.parse(text(forecast.getAsJsonObject(), "date"));
          min = min == null || date.isBefore(min) ? date : min;
          max = max == null || date.isAfter(max) ? date : max;
        }
        if (min == null || start.isBefore(min) || end.isAfter(max)) return false;
      }
      return true;
    } catch (RuntimeException error) { return false; }
  }
}
