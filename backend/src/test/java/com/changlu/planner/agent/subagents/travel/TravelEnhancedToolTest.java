package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.agent.subagents.travel.tools.OpeningHoursTool;
import com.changlu.planner.agent.subagents.travel.tools.RouteEstimateTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelPlanValidationTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelWeatherTool;
import com.changlu.planner.agent.subagents.travel.tools.support.AmapClient;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TravelEnhancedToolTest {
  @Test void weatherReturnsStructuredForecastAndCoverage() throws Exception {
    AmapClient amap = new AmapClient("test") {
      @Override public JsonObject weather(String location) {
        return JsonParser.parseString("""
            {"location":"杭州","city":"杭州市","queriedAt":"2026-08-05T00:00:00Z",
            "forecasts":[{"date":"2026-08-15","dayWeather":"晴"},{"date":"2026-08-16","dayWeather":"多云"}]}
            """).getAsJsonObject();
      }
    };
    JsonObject arguments = JsonParser.parseString("""
        {"locations":["杭州"],"startDate":"2026-08-15","endDate":"2026-08-16"}
        """).getAsJsonObject();
    AgentResult result = new TravelWeatherTool(amap).execute(call(TravelWeatherTool.NAME, arguments), context());
    JsonObject weather = result.data().getAsJsonObject("weather");
    assertTrue(weather.get("forecastCoversTrip").getAsBoolean());
    assertEquals(1, weather.getAsJsonArray("items").size());
  }

  @Test void routeKeepsSegmentMetadataAndEstimate() throws Exception {
    AmapClient amap = new AmapClient("test") {
      @Override public JsonObject route(String origin, String destination, String city) {
        JsonObject result = new JsonObject();
        result.addProperty("distanceMeters", 12000);
        result.addProperty("durationMinutes", 35);
        result.addProperty("mode", "driving");
        result.addProperty("estimated", true);
        return result;
      }
    };
    JsonObject arguments = JsonParser.parseString("""
        {"segments":[{"origin":"西湖","destination":"灵隐寺","city":"杭州","date":"2026-08-15"}]}
        """).getAsJsonObject();
    AgentResult result = new RouteEstimateTool(amap).execute(call(RouteEstimateTool.NAME, arguments), context());
    JsonObject route = result.data().getAsJsonArray("routes").get(0).getAsJsonObject();
    assertEquals(35, route.get("durationMinutes").getAsInt());
    assertEquals("2026-08-15", route.get("date").getAsString());
  }

  @Test void openingHoursExtractsTimeAndClosedDayFromSources() throws Exception {
    OpeningHoursTool tool = new OpeningHoursTool((query, limit, refresh) -> List.of(
        new WebSearchTool.Result("博物馆参观须知", "开放时间 09:00-17:00，周一闭馆",
            "official.example", "https://official.example/open")));
    JsonObject arguments = JsonParser.parseString("""
        {"places":[{"name":"浙江省博物馆","city":"杭州","date":"2026-08-17"}]}
        """).getAsJsonObject();
    AgentResult result = tool.execute(call(OpeningHoursTool.NAME, arguments), context());
    JsonObject opening = result.data().getAsJsonArray("openingHours").get(0).getAsJsonObject();
    assertTrue(opening.get("openingHours").getAsString().contains("09:00-17:00"));
    assertEquals("周一闭馆", opening.getAsJsonArray("closedDays").get(0).getAsString());
    assertTrue(opening.get("verificationRequired").getAsBoolean());
  }

  @Test void validationFindsBudgetOverrunOverlapAndHighIntensity() throws Exception {
    JsonObject arguments = JsonParser.parseString("""
        {"request":{"budget":{"amount":3000,"currency":"CNY"}},
        "budgetEstimate":{"amount":3800,"breakdown":[{"category":"transport","amount":1000},
        {"category":"accommodation","amount":1800},{"category":"food","amount":1000}]},
        "days":[{"date":"2026-08-15","activities":[
        {"startTime":"06:30","durationMinutes":180,"location":"A"},
        {"startTime":"08:00","durationMinutes":180,"location":"B"},
        {"startTime":"11:00","durationMinutes":120,"location":"C"},
        {"startTime":"13:00","durationMinutes":120,"location":"D"},
        {"startTime":"15:00","durationMinutes":120,"location":"E"},
        {"startTime":"17:00","durationMinutes":120,"location":"F"}]}],
        "routes":[{"date":"2026-08-15","durationMinutes":180}],"openingHours":[]}
        """).getAsJsonObject();
    AgentResult result = new TravelPlanValidationTool().execute(
        call(TravelPlanValidationTool.NAME, arguments), context());
    JsonObject validation = result.data().getAsJsonObject("validation");
    assertEquals("high", validation.get("level").getAsString());
    assertFalse(validation.getAsJsonArray("issues").isEmpty());
    assertTrue(validation.getAsJsonArray("issues").toString().contains("BUDGET_EXCEEDED"));
    assertTrue(validation.getAsJsonArray("issues").toString().contains("TRAVEL_TIME_OVERLAP"));
  }

  private ToolCall call(String name, JsonObject arguments) {
    return new ToolCall("test:" + name, null, name, arguments);
  }

  private AgentContext context() {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", Set.of("travel:read"),
        Instant.now().plusSeconds(30), new JsonObject());
  }
}
