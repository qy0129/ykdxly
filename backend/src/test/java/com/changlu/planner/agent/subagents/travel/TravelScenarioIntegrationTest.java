package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.*;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.travel.tools.AttractionResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.DestinationResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.LocationContextTool;
import com.changlu.planner.agent.subagents.travel.tools.MapRoutingTool;
import com.changlu.planner.agent.subagents.travel.tools.WeatherForecastTool;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Prompt-level scenarios. Every external boundary is a deterministic Fake. */
final class TravelScenarioIntegrationTest {
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-05T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

  @Test void qingdaoTenDayBeachPlanUsesVerifiedCandidatesAndReportsWeatherGap() throws Exception {
    try (ToolRegistry tools = fakeTools()) {
      AgentResult result = subagent(tools).execute(new SubagentRequest(
          "明天去青岛玩十天，预算 10 万，喜欢海边，不要太累。", new JsonObject(), List.of()), context());

      assertEquals(AgentStatus.WAITING_USER, result.status());
      JsonObject request = result.data().getAsJsonObject("request");
      assertEquals("2026-08-06", request.get("startDate").getAsString());
      assertEquals("2026-08-15", request.get("endDate").getAsString());
      assertEquals(100000, request.getAsJsonObject("budget").get("amount").getAsInt());
      assertTrue(request.get("beachPreference").getAsBoolean());
      assertEquals("relaxed", request.get("pace").getAsString());
      assertEquals(10, result.data().getAsJsonArray("days").size());
      assertTrue(riskCodes(result).contains("WEATHER_FORECAST_COVERAGE_INCOMPLETE"));

      Set<String> validIds = Set.of("amap:beach", "amap:museum", "amap:mountain");
      int restDays = 0;
      for (JsonElement day : result.data().getAsJsonArray("days")) {
        JsonArray activities = day.getAsJsonObject().getAsJsonArray("activities");
        if (activities.isEmpty()) restDays++;
        for (JsonElement activity : activities) assertTrue(validIds.contains(activity.getAsJsonObject().get("attractionId").getAsString()));
      }
      assertTrue(restDays >= 7, "候选不足时应保留休息日，不虚构景点");
    }
  }

  @Test void sanyaParentsPlanSlowsPaceAndDoesNotInventRailService() throws Exception {
    JsonObject arguments = new JsonObject(); arguments.addProperty("origin", "上海");
    arguments.addProperty("startDate", "2026-08-20");
    try (ToolRegistry tools = fakeTools()) {
      AgentResult result = subagent(tools).execute(new SubagentRequest(
          "带父母去三亚五天，四星级酒店，高铁优先，预算一万。", arguments, List.of()), context());

      JsonObject request = result.data().getAsJsonObject("request");
      assertEquals("三亚", request.get("destination").getAsString());
      assertEquals("2026-08-24", request.get("endDate").getAsString());
      assertTrue(request.get("elderlyTravel").getAsBoolean());
      assertEquals(4, request.get("hotelStarRating").getAsInt());
      assertEquals("highSpeedRail", request.get("preferredTransport").getAsString());
      assertEquals("relaxed", request.get("pace").getAsString());
      assertTrue(riskCodes(result).contains("INTERCITY_TRANSPORT_VERIFICATION_REQUIRED"));
      for (JsonElement day : result.data().getAsJsonArray("days")) {
        assertEquals(60, day.getAsJsonObject().get("restBufferMinutes").getAsInt());
        int activityMinutes = 0;
        for (JsonElement activity : day.getAsJsonObject().getAsJsonArray("activities")) activityMinutes += activity.getAsJsonObject().get("durationMinutes").getAsInt();
        assertTrue(activityMinutes <= 240);
      }
    }
  }

  @Test void nextMondayBeijingPlanStartsEveryDayAfterNine() throws Exception {
    try (ToolRegistry tools = fakeTools()) {
      AgentResult result = subagent(tools).execute(new SubagentRequest(
          "下周一去北京三天，不要早起。", new JsonObject(), List.of()), context());
      JsonObject request = result.data().getAsJsonObject("request");
      assertEquals("2026-08-10", request.get("startDate").getAsString());
      assertEquals("2026-08-12", request.get("endDate").getAsString());
      for (JsonElement day : result.data().getAsJsonArray("days")) {
        JsonArray activities = day.getAsJsonObject().getAsJsonArray("activities");
        assertFalse(activities.isEmpty());
        assertTrue(activities.get(0).getAsJsonObject().get("startTime").getAsString().compareTo("09:00") >= 0);
      }
    }
  }

  private TravelSubagent subagent(ToolRegistry tools) {
    TravelPlannerModel model = (request, facts, sharedContext) -> {
      JsonObject generated = new JsonObject(); generated.addProperty("message", "已生成可执行旅行计划。");
      generated.add("request", request.arguments().deepCopy()); JsonArray days = new JsonArray();
      LocalDate start = LocalDate.parse(request.arguments().get("startDate").getAsString());
      LocalDate end = LocalDate.parse(request.arguments().get("endDate").getAsString());
      JsonArray candidates = facts.getAsJsonArray("attractions"); int index = 0;
      for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1), index++) {
        JsonObject day = new JsonObject(); day.addProperty("date", date.toString());
        day.addProperty("title", index < candidates.size() ? "核实景点日" : "休息与自由活动"); JsonArray activities = new JsonArray();
        if (index < candidates.size()) {
          JsonObject candidate = candidates.get(index).getAsJsonObject(); JsonObject activity = new JsonObject();
          activity.addProperty("attractionId", candidate.get("attractionId").getAsString());
          activity.addProperty("title", candidate.get("name").getAsString()); activity.addProperty("startTime", "07:30");
          activity.addProperty("durationMinutes", 90); activity.addProperty("indoor", index == 1); activities.add(activity);
        }
        day.add("activities", activities); days.add(day);
      }
      generated.add("days", days); generated.add("preparationTasks", new JsonArray());
      generated.add("budgetEstimate", new JsonObject()); generated.add("risks", new JsonArray());
      generated.add("questions", new JsonArray()); generated.add("alternativePlans", new JsonArray());
      generated.addProperty("planningInstruction", "按逐日活动创建旅行计划和日程草案"); return generated;
    };
    return new TravelSubagent(model, tools, new TravelPolicy(CLOCK), new JsonObject(), new JsonObject());
  }

  private ToolRegistry fakeTools() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(fake(LocationContextTool.NAME, call -> {
      JsonObject location = new JsonObject(); location.addProperty("destinationName", text(call, "destination"));
      location.addProperty("destinationLat", 36.0671); location.addProperty("destinationLng", 120.3826);
      location.addProperty("coordinateSystem", "GCJ02"); location.addProperty("timezone", "Asia/Shanghai");
      location.addProperty("provider", "fake-amap"); JsonObject data = new JsonObject(); data.add("locationContext", location); return data;
    }));
    tools.register(fake(WeatherForecastTool.NAME, call -> {
      JsonArray weather = new JsonArray(); LocalDate start = LocalDate.parse(text(call, "startDate"));
      LocalDate end = LocalDate.parse(text(call, "endDate"));
      for (LocalDate date = start; !date.isAfter(end) && weather.size() < 7; date = date.plusDays(1)) {
        JsonObject day = new JsonObject(); day.addProperty("date", date.toString()); day.addProperty("condition", "晴");
        day.addProperty("precipitationProbability", 10); day.addProperty("provider", "fake-qweather"); weather.add(day);
      }
      JsonObject data = new JsonObject(); data.add("weather", weather); return data;
    }));
    tools.register(fake(AttractionResearchTool.NAME, call -> {
      JsonArray values = new JsonArray();
      attraction(values, "amap:beach", "海滨公园", false); attraction(values, "amap:museum", "城市博物馆", true);
      attraction(values, "amap:mountain", "城市观景区", false); JsonObject data = new JsonObject(); data.add("attractions", values); return data;
    }));
    tools.register(fake(DestinationResearchTool.NAME, call -> {
      JsonArray sources = new JsonArray(); JsonObject source = new JsonObject(); source.addProperty("provider", "fake-search");
      source.addProperty("sourceUrl", "https://example.test/travel"); source.addProperty("fetchedAt", "2026-08-05T02:00:00Z"); sources.add(source);
      JsonObject data = new JsonObject(); data.add("sources", sources); return data;
    }));
    tools.register(fake(MapRoutingTool.NAME, call -> { JsonObject data = new JsonObject(); data.add("transitMatrix", new JsonArray()); return data; }));
    return tools;
  }

  private ToolHandler fake(String name, FakeWork work) {
    return new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(name, "1.0", name, new JsonObject(), new JsonObject(), Set.of("travel:read"),
            ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false, Duration.ofSeconds(2), RetryPolicy.none());
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
        return AgentResult.completed(name, work.execute(call.arguments()), context.traceId());
      }
    };
  }

  private void attraction(JsonArray values, String id, String name, boolean indoor) {
    JsonObject item = new JsonObject(); item.addProperty("attractionId", id); item.addProperty("name", name);
    item.addProperty("lat", 36.0 + values.size() * 0.01); item.addProperty("lng", 120.3 + values.size() * 0.01);
    item.addProperty("coordinateSystem", "GCJ02"); item.addProperty("indoor", indoor);
    item.addProperty("provider", "fake-amap"); item.addProperty("sourceUrl", "https://example.test/" + id.substring(5)); values.add(item);
  }

  private Set<String> riskCodes(AgentResult result) {
    Set<String> codes = new HashSet<>();
    for (JsonElement risk : result.data().getAsJsonArray("risks")) if (risk.isJsonObject() && risk.getAsJsonObject().has("code")) codes.add(risk.getAsJsonObject().get("code").getAsString());
    return codes;
  }

  private String text(JsonObject value, String name) { return value.has(name) ? value.get(name).getAsString() : ""; }
  private AgentContext context() { return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
      new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", Set.of("travel:read", "planning:write"),
      Instant.now().plusSeconds(10), new JsonObject()); }
  @FunctionalInterface private interface FakeWork { JsonObject execute(JsonObject arguments) throws Exception; }
}
