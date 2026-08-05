package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Searches public snippets for opening hours and deliberately marks them as unverified. */
public final class OpeningHoursTool implements ToolHandler {
  public static final String NAME = "travel.opening-hours";
  private static final Pattern TIME = Pattern.compile("(?:[01]?\\d|2[0-3])[:：][0-5]\\d(?:\\s*[-至~～]\\s*(?:[01]?\\d|2[0-3])[:：][0-5]\\d)?");
  private static final Pattern CLOSED_DAY = Pattern.compile("(?:周|星期)[一二三四五六日天](?:闭馆|休息|不开)");

  @FunctionalInterface
  public interface SearchProvider {
    List<WebSearchTool.Result> search(String query, int limit, boolean refresh);
  }

  private final SearchProvider search;

  public OpeningHoursTool(WebSearchTool search) { this(search::search); }

  public OpeningHoursTool(SearchProvider search) { this.search = search; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"places":{"type":"array","minItems":1,"maxItems":8,
        "items":{"type":"object","properties":{"name":{"type":"string","minLength":1},
        "city":{"type":"string"},"date":{"type":"string"}},"required":["name"]}}},"required":["places"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString(
        "{\"type\":\"object\",\"properties\":{\"openingHours\":{\"type\":\"array\"}},\"required\":[\"openingHours\"]}")
        .getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "搜索景点营业时间、闭馆信息和公开来源", input, output,
        Set.of("travel:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false,
        java.time.Duration.ofSeconds(30), RetryPolicy.readOnlyNetwork());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    JsonArray places = array(call.arguments(), "places");
    if (places.isEmpty()) throw new IllegalArgumentException("TRAVEL_OPENING_HOURS_PLACES_REQUIRED");
    JsonArray results = new JsonArray();
    for (JsonElement element : places) {
      if (!element.isJsonObject()) throw new IllegalArgumentException("TRAVEL_OPENING_HOURS_PLACE_INVALID");
      JsonObject place = element.getAsJsonObject();
      String name = text(place, "name").trim();
      if (name.isBlank()) throw new IllegalArgumentException("TRAVEL_OPENING_HOURS_NAME_REQUIRED");
      String city = text(place, "city").trim();
      String query = (city.isBlank() ? "" : city + " ") + name + " 营业时间 闭馆 官方";
      List<WebSearchTool.Result> sources = search.search(query, 5, false);
      JsonObject result = new JsonObject();
      result.addProperty("place", name);
      if (!city.isBlank()) result.addProperty("city", city);
      copyIfPresent(place, result, "date");
      result.addProperty("status", "unverified");
      result.addProperty("verificationRequired", true);
      result.addProperty("queriedAt", Instant.now().toString());
      JsonArray sourceRows = new JsonArray();
      List<String> hours = new ArrayList<>();
      List<String> closedDays = new ArrayList<>();
      for (WebSearchTool.Result source : sources) {
        String text = source.title() + " " + source.summary();
        Matcher time = TIME.matcher(text);
        while (time.find() && hours.size() < 4) hours.add(time.group());
        Matcher closed = CLOSED_DAY.matcher(text);
        while (closed.find() && closedDays.size() < 7) closedDays.add(closed.group());
        JsonObject row = new JsonObject();
        row.addProperty("title", source.title());
        row.addProperty("summary", source.summary());
        row.addProperty("source", source.source());
        row.addProperty("url", source.url());
        sourceRows.add(row);
      }
      result.addProperty("openingHours", String.join("; ", hours));
      JsonArray closed = new JsonArray();
      closedDays.stream().distinct().forEach(closed::add);
      result.add("closedDays", closed);
      result.add("sources", sourceRows);
      results.add(result);
    }
    JsonObject data = new JsonObject();
    data.add("openingHours", results);
    return AgentResult.completed("已搜索景点营业时间参考，仍需以官方临近公告为准", data, context.traceId());
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
