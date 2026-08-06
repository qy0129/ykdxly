package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class TravelOptimizerTest {
  @Test void adjustsEarlyActivityAndBackfillsAttractionFacts() {
    TravelRequest request = TravelRequest.from(JsonParser.parseString("""
        {"startDate":"2026-09-01","endDate":"2026-09-01","pace":"relaxed","avoidEarlyMorning":true}
        """).getAsJsonObject());
    JsonArray days = JsonParser.parseString("""
        [{"date":"2026-09-01","activities":[{"attractionId":"amap:1","startTime":"07:30","durationMinutes":90,"title":"海边"}]}]
        """).getAsJsonArray();
    JsonArray attractions = JsonParser.parseString("""
        [{"attractionId":"amap:1","name":"海滨公园","lat":36.1,"lng":120.3,"coordinateSystem":"GCJ02"}]
        """).getAsJsonArray();
    ItineraryOptimizer.Result result = new ItineraryOptimizer().optimize(request, days, new JsonArray(), attractions);
    JsonObject activity = result.days().get(0).getAsJsonObject().getAsJsonArray("activities").get(0).getAsJsonObject();
    assertEquals("09:00", activity.get("startTime").getAsString());
    assertEquals("GCJ02", activity.get("coordinateSystem").getAsString());
  }

  @Test void reportsRainAndDailyLoadConflicts() {
    TravelRequest request = TravelRequest.from(JsonParser.parseString("{\"pace\":\"relaxed\"}").getAsJsonObject());
    JsonArray days = JsonParser.parseString("""
        [{"date":"2026-09-01","activities":[{"durationMinutes":300,"title":"户外","indoor":false}]}]
        """).getAsJsonArray();
    JsonArray weather = JsonParser.parseString("[{\"date\":\"2026-09-01\",\"precipitationProbability\":80}]").getAsJsonArray();
    JsonArray conflicts = new ItineraryOptimizer().optimize(request, days, weather, new JsonArray()).conflicts();
    assertEquals(2, conflicts.size());
    assertTrue(conflicts.get(0).getAsJsonObject().has("message"));
  }

  @Test void reportsOverlappingActivitiesBeforeDraftGeneration() {
    TravelRequest request = TravelRequest.from(JsonParser.parseString("{\"pace\":\"balanced\"}").getAsJsonObject());
    JsonArray days = JsonParser.parseString("""
        [{"date":"2026-09-01","activities":[
          {"title":"A","startTime":"10:00","durationMinutes":120},
          {"title":"B","startTime":"11:30","durationMinutes":90}
        ]}]
        """).getAsJsonArray();

    JsonArray conflicts = new ItineraryOptimizer().optimize(request, days, new JsonArray(), new JsonArray()).conflicts();

    assertTrue(conflicts.asList().stream().anyMatch(item -> "ACTIVITY_TIME_OVERLAP".equals(
        item.getAsJsonObject().get("code").getAsString())));
  }

  @Test void externalFailureCodesHaveUserFacingMessages() {
    assertEquals("天气服务未配置或暂时不可用，将在服务恢复后或出发前刷新。",
        TravelDataCollector.userMessage("WEATHER_UNAVAILABLE"));
  }

  @Test void budgetEngineReportsSufficientAndInsufficientBudgets() {
    JsonObject generous = JsonParser.parseString("""
        {"startDate":"2026-09-01","endDate":"2026-09-05","travelers":1,"budget":{"amount":100000,"currency":"CNY"}}
        """).getAsJsonObject();
    JsonObject small = generous.deepCopy(); small.getAsJsonObject("budget").addProperty("amount", 100);
    assertFalse(new BudgetEngine().estimate(TravelRequest.from(generous)).get("overBudget").getAsBoolean());
    assertTrue(new BudgetEngine().estimate(TravelRequest.from(small)).get("overBudget").getAsBoolean());
  }
}
