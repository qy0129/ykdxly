package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Deterministic checks for budget, daily load, overlaps and opening-hour warnings. */
public final class TravelPlanValidationTool implements ToolHandler {
  public static final String NAME = "travel.plan.validate";

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"request":{"type":"object"},"days":{"type":"array"},
        "budgetEstimate":{"type":"object"},"routes":{"type":"array"},"openingHours":{"type":"array"}},
        "required":["request","days","budgetEstimate"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString(
        "{\"type\":\"object\",\"properties\":{\"validation\":{\"type\":\"object\"}},\"required\":[\"validation\"]}")
        .getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "检查旅行预算、行程强度、时间重叠和营业时间风险", input, output,
        Set.of("travel:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false,
        java.time.Duration.ofSeconds(5), RetryPolicy.none());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    JsonObject args = call.arguments();
    JsonObject request = object(args, "request");
    JsonArray days = array(args, "days");
    JsonObject budget = object(args, "budgetEstimate");
    JsonArray routes = array(args, "routes");
    JsonArray openingHours = array(args, "openingHours");
    JsonArray issues = new JsonArray();
    JsonObject budgetCheck = budgetCheck(request, budget, issues);
    JsonObject intensity = intensityCheck(days, routes, issues);
    openingCheck(days, openingHours, issues);

    String level = "low";
    for (JsonElement issue : issues) {
      String issueLevel = text(issue.getAsJsonObject(), "level");
      if ("high".equals(issueLevel)) level = "high";
      else if ("medium".equals(issueLevel) && "low".equals(level)) level = "medium";
    }
    JsonObject validation = new JsonObject();
    validation.addProperty("level", level);
    validation.add("budget", budgetCheck);
    validation.add("intensity", intensity);
    validation.add("issues", issues);
    validation.addProperty("verificationRequired", !issues.isEmpty());
    JsonObject data = new JsonObject();
    data.add("validation", validation);
    return AgentResult.completed(issues.isEmpty() ? "旅行方案检查通过" : "旅行方案已检查，存在需要核实或调整的项目",
        data, context.traceId());
  }

  private JsonObject budgetCheck(JsonObject request, JsonObject estimate, JsonArray issues) {
    double requested = number(object(request, "budget"), "amount", 0);
    double estimated = number(estimate, "amount", 0);
    double breakdownTotal = 0;
    Set<String> categories = new HashSet<>();
    for (JsonElement element : array(estimate, "breakdown")) {
      if (!element.isJsonObject()) continue;
      JsonObject row = element.getAsJsonObject();
      breakdownTotal += number(row, "amount", 0);
      categories.add(text(row, "category").toLowerCase());
    }
    JsonObject result = new JsonObject();
    result.addProperty("requestedAmount", requested);
    result.addProperty("estimatedAmount", estimated);
    result.addProperty("breakdownAmount", breakdownTotal);
    result.addProperty("currency", text(object(request, "budget"), "currency"));
    result.addProperty("estimated", true);
    if (requested > 0 && estimated > requested) addIssue(issues, "BUDGET_EXCEEDED", "预算估算超过用户预算", "high");
    if (estimated > 0 && breakdownTotal > 0 && Math.abs(estimated - breakdownTotal) > Math.max(10, estimated * .05)) {
      addIssue(issues, "BUDGET_BREAKDOWN_MISMATCH", "预算分类之和与总额不一致", "medium");
    }
    if (!categories.isEmpty() && !containsCategory(categories, "交通", "transport") ) {
      addIssue(issues, "BUDGET_TRANSPORT_MISSING", "预算中缺少交通分类", "medium");
    }
    if (!categories.isEmpty() && !containsCategory(categories, "住宿", "accommodation", "hotel")) {
      addIssue(issues, "BUDGET_ACCOMMODATION_MISSING", "预算中缺少住宿分类", "medium");
    }
    return result;
  }

  private JsonObject intensityCheck(JsonArray days, JsonArray routes, JsonArray issues) {
    Map<String, Integer> routeMinutes = new HashMap<>();
    for (JsonElement element : routes) {
      JsonObject route = element.getAsJsonObject();
      String date = text(route, "date");
      routeMinutes.merge(date, (int) number(route, "durationMinutes", 0), Integer::sum);
    }
    int maximumActivities = 0;
    int maximumMinutes = 0;
    for (JsonElement element : days) {
      JsonObject day = element.getAsJsonObject();
      String date = text(day, "date");
      JsonArray activities = array(day, "activities");
      int activityMinutes = 0;
      int earliest = Integer.MAX_VALUE;
      int latest = Integer.MIN_VALUE;
      int previousEnd = -1;
      for (JsonElement activityElement : activities) {
        JsonObject activity = activityElement.getAsJsonObject();
        int start = minute(text(activity, "startTime"));
        int duration = (int) number(activity, "durationMinutes", 0);
        if (start >= 0) {
          if (previousEnd > start) addIssue(issues, "TRAVEL_TIME_OVERLAP", date + " 存在活动时间重叠", "high");
          previousEnd = Math.max(previousEnd, start + duration);
          earliest = Math.min(earliest, start);
          latest = Math.max(latest, start + duration);
        }
        activityMinutes += Math.max(0, duration);
      }
      int totalMinutes = activityMinutes + routeMinutes.getOrDefault(date, 0);
      maximumActivities = Math.max(maximumActivities, activities.size());
      maximumMinutes = Math.max(maximumMinutes, totalMinutes);
      if (activities.size() > 5) addIssue(issues, "TRAVEL_TOO_MANY_ACTIVITIES", date + " 安排了超过 5 个活动", "high");
      else if (activities.size() > 4) addIssue(issues, "TRAVEL_BUSY_DAY", date + " 活动数量偏多", "medium");
      if (totalMinutes > 720) addIssue(issues, "TRAVEL_DAY_TOO_LONG", date + " 活动和交通预计超过 12 小时", "high");
      else if (totalMinutes > 600) addIssue(issues, "TRAVEL_DAY_BUSY", date + " 活动和交通预计超过 10 小时", "medium");
      if (earliest < 7 * 60) addIssue(issues, "TRAVEL_EARLY_START", date + " 出发时间早于 07:00", "medium");
      if (latest > 22 * 60) addIssue(issues, "TRAVEL_LATE_END", date + " 行程结束晚于 22:00", "medium");
    }
    JsonObject result = new JsonObject();
    result.addProperty("maxActivities", maximumActivities);
    result.addProperty("maxTotalMinutes", maximumMinutes);
    result.addProperty("checkedDays", days.size());
    return result;
  }

  private void openingCheck(JsonArray days, JsonArray openingHours, JsonArray issues) {
    Map<String, JsonObject> byPlace = new HashMap<>();
    for (JsonElement element : openingHours) {
      JsonObject row = element.getAsJsonObject();
      byPlace.put(text(row, "place"), row);
      if ("unverified".equals(text(row, "status"))) {
        addIssue(issues, "OPENING_HOURS_UNVERIFIED", text(row, "place") + "营业时间需要再次核实", "medium");
      }
    }
    for (JsonElement dayElement : days) {
      JsonObject day = dayElement.getAsJsonObject();
      String date = text(day, "date");
      String weekday = weekday(date);
      for (JsonElement activityElement : array(day, "activities")) {
        JsonObject activity = activityElement.getAsJsonObject();
        String place = text(activity, "location");
        JsonObject opening = byPlace.get(place);
        if (opening == null) continue;
        for (JsonElement closed : array(opening, "closedDays")) {
          if (closed.getAsString().contains(weekday)) {
            addIssue(issues, "ATTRACTION_CLOSED", place + "可能在" + weekday + "闭馆", "high");
          }
        }
      }
    }
  }

  private String weekday(String value) {
    try {
      return switch (LocalDate.parse(value).getDayOfWeek()) {
        case MONDAY -> "周一"; case TUESDAY -> "周二"; case WEDNESDAY -> "周三";
        case THURSDAY -> "周四"; case FRIDAY -> "周五"; case SATURDAY -> "周六"; case SUNDAY -> "周日";
      };
    } catch (DateTimeParseException error) { return ""; }
  }

  private int minute(String value) {
    if (value == null || value.isBlank()) return -1;
    try { return LocalTime.parse(value.replace('：', ':')).getHour() * 60 + LocalTime.parse(value.replace('：', ':')).getMinute(); }
    catch (DateTimeParseException error) { return -1; }
  }

  private boolean containsCategory(Set<String> values, String... expected) {
    for (String value : values) for (String candidate : expected) if (value.contains(candidate)) return true;
    return false;
  }

  private void addIssue(JsonArray issues, String code, String message, String level) {
    JsonObject issue = new JsonObject();
    issue.addProperty("code", code); issue.addProperty("message", message); issue.addProperty("level", level);
    issue.addProperty("verificationRequired", true); issues.add(issue);
  }

  private JsonObject object(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : new JsonObject();
  }
  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
  }
  private String text(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
  }
  private double number(JsonObject object, String name, double fallback) {
    try { return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsDouble() : fallback; }
    catch (RuntimeException error) { return fallback; }
  }
}
