package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class TravelPlanRevisionTest {
  @Test void patchesOnlyTheRequestedDayWithFreshAttractionData() {
    JsonObject previous = new JsonObject();
    JsonArray days = new JsonArray();
    days.add(day("2026-08-06", "Day one", "Morning walk"));
    days.add(day("2026-08-07", "Day two", "Afternoon stroll"));
    previous.add("days", days);
    JsonArray attractions = new JsonArray();
    JsonObject attraction = new JsonObject();
    attraction.addProperty("attractionId", "amap:laoshan");
    attraction.addProperty("name", "崂山");
    attraction.addProperty("address", "Laoshan district");
    attractions.add(attraction);

    JsonObject revised = TravelPlanRevision.apply(previous, "第2天下午改成崂山，14:30出发", attractions);

    assertNotNull(revised);
    assertEquals("Morning walk", revised.getAsJsonArray("days").get(0).getAsJsonObject()
        .getAsJsonArray("activities").get(0).getAsJsonObject().get("title").getAsString());
    JsonObject changed = revised.getAsJsonArray("days").get(1).getAsJsonObject()
        .getAsJsonArray("activities").get(0).getAsJsonObject();
    assertEquals("崂山", changed.get("title").getAsString());
    assertEquals("14:30", changed.get("startTime").getAsString());
    assertEquals("localized", revised.get("revisionMode").getAsString());
  }

  @Test void recognizesWholePlanRequestsAsModelReplanning() {
    assertTrue(TravelPlanRevision.requiresFullReplan("请重新规划整个计划，换一版"));
    assertTrue(TravelPlanRevision.requiresFullReplan("第3天保留不变，但请重新规划整个计划"));
    assertFalse(TravelPlanRevision.requiresFullReplan("第3天下午改成崂山"));
  }

  @Test void bindsThePatchToTheReplacementClauseBeforeLaterKeepAsIsText() {
    JsonObject previous = new JsonObject();
    JsonArray days = new JsonArray();
    days.add(day("2026-08-06", "Day one", "Old one"));
    days.add(day("2026-08-07", "Day two", "Old two"));
    days.add(day("2026-08-08", "Day three", "Old three"));
    days.add(day("2026-08-09", "Day four", "Old four"));
    previous.add("days", days);
    JsonObject attraction = new JsonObject();
    attraction.addProperty("attractionId", "amap:shilaoren");
    attraction.addProperty("name", "石老人海水浴场");
    JsonArray attractions = new JsonArray(); attractions.add(attraction);

    JsonObject revised = TravelPlanRevision.apply(previous,
        "只修改第3天上午：改成石老人海水浴场，10:00开始，停留120分钟。第4天保持14:00安排不变。", attractions);

    JsonObject changed = revised.getAsJsonArray("days").get(2).getAsJsonObject().getAsJsonArray("activities").get(0).getAsJsonObject();
    assertEquals("石老人海水浴场", changed.get("title").getAsString());
    assertEquals("10:00", changed.get("startTime").getAsString());
    assertEquals(120, changed.get("durationMinutes").getAsInt());
    assertEquals("Old four", revised.getAsJsonArray("days").get(3).getAsJsonObject().getAsJsonArray("activities").get(0).getAsJsonObject().get("title").getAsString());
    assertEquals(1, revised.getAsJsonArray("revisionDiff").size());
  }

  @Test void movesTheReferencedTimeBlockToItsReplacementPeriod() {
    JsonObject previous = new JsonObject();
    JsonArray days = new JsonArray();
    days.add(day("2026-08-06", "Day one", "上午：海边散步"));
    previous.add("days", days);

    JsonObject revised = TravelPlanRevision.apply(previous, "第1天上午改成下午", new JsonArray());

    assertNotNull(revised);
    assertEquals("14:00", revised.getAsJsonArray("days").get(0).getAsJsonObject()
        .getAsJsonArray("activities").get(0).getAsJsonObject().get("startTime").getAsString());
  }

  @Test void usesTheLastModificationTargetWhenHistoryIsIncludedInTheMessage() {
    JsonObject previous = new JsonObject();
    JsonArray days = new JsonArray();
    days.add(day("2026-08-07", "Day one", "五四广场"));
    days.add(day("2026-08-08", "Day two", "八大关"));
    days.add(day("2026-08-09", "Day three", "青岛海底世界"));
    previous.add("days", days);
    JsonArray attractions = new JsonArray();
    JsonObject attraction = new JsonObject();
    attraction.addProperty("attractionId", "amap:shilaoren");
    attraction.addProperty("name", "石老人海水浴场");
    attraction.addProperty("address", "崂山区海尔路");
    attractions.add(attraction);

    JsonObject revised = TravelPlanRevision.apply(previous,
        "第 1 天上午 09:30 五四广场。第 2 天下午 15:00 八大关。只修改第3天上午的安排："
            + "把原来的上午景点改成石老人海水浴场，10:00开始，停留120分钟。其他日期保持不变。",
        attractions);

    assertNotNull(revised);
    JsonArray revisedDays = revised.getAsJsonArray("days");
    assertEquals("五四广场", revisedDays.get(0).getAsJsonObject().getAsJsonArray("activities")
        .get(0).getAsJsonObject().get("title").getAsString());
    JsonObject changed = revisedDays.get(2).getAsJsonObject().getAsJsonArray("activities").get(0).getAsJsonObject();
    assertEquals("石老人海水浴场", changed.get("title").getAsString());
    assertEquals("10:00", changed.get("startTime").getAsString());
    assertEquals(120, changed.get("durationMinutes").getAsInt());
  }

  private JsonObject day(String date, String title, String activityTitle) {
    JsonObject day = new JsonObject();
    day.addProperty("date", date); day.addProperty("title", title);
    JsonObject activity = new JsonObject();
    activity.addProperty("title", activityTitle); activity.addProperty("durationMinutes", 90);
    JsonArray activities = new JsonArray(); activities.add(activity); day.add("activities", activities);
    return day;
  }
}
