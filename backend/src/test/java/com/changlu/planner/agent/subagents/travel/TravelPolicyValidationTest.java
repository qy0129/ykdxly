package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TravelPolicyValidationTest {
  private final TravelPolicy policy = new TravelPolicy();

  @Test void rejectsDuplicateDayDates() {
    JsonArray days = new JsonArray();
    days.add(day("2026-09-01"));
    days.add(day("2026-09-01"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate(result(days)));
  }

  @Test void rejectsDayOutsideRequestedRange() {
    JsonArray days = new JsonArray();
    days.add(day("2026-09-04"));
    assertThrows(IllegalArgumentException.class, () -> policy.validate(result(days)));
  }

  @Test void acceptsDistinctDaysInsideRequestedRange() {
    JsonArray days = new JsonArray();
    days.add(day("2026-09-01"));
    days.add(day("2026-09-03"));
    assertDoesNotThrow(() -> policy.validate(result(days)));
  }

  @Test void currentPreviewInstructionOverridesReusedWritePreferenceAndApprovalText() {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("saveToPlanner", true);
    String message = "确认行程，生成草案。只修改第3天上午，不要写入日历，只生成方案预览。";

    SubagentRequest normalized = policy.normalizeRequest(new SubagentRequest(message, arguments, List.of()));

    assertFalse(normalized.arguments().get("saveToPlanner").getAsBoolean());
    assertFalse(policy.writeRequested(message, normalized.arguments()));
    assertFalse(policy.planApproved(message, normalized.arguments()));
  }

  private JsonObject day(String date) {
    JsonObject day = new JsonObject();
    day.addProperty("date", date);
    day.add("activities", new JsonArray());
    return day;
  }

  private TravelResult result(JsonArray days) {
    JsonObject request = new JsonObject();
    request.addProperty("destination", "Beijing");
    request.addProperty("startDate", "2026-09-01");
    request.addProperty("endDate", "2026-09-03");
    request.addProperty("travelers", 1);
    return new TravelResult("plan", TravelRequest.from(request), days, new JsonArray(),
        new JsonObject(), new JsonArray(), new JsonArray(), new JsonArray(), "plan",
        new JsonObject(), new JsonArray(), new JsonArray(), new JsonArray(), new JsonArray());
  }
}
