package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ItineraryOptimizer {
  public record Constraints(String pace, boolean avoidEarlyMorning, boolean elderlyTravel) {}
  public record Result(JsonArray days, JsonArray conflicts) {}

  public Result optimize(TravelRequest request, JsonArray inputDays, JsonArray weather, JsonArray attractions) {
    JsonArray days = inputDays.deepCopy(); JsonArray conflicts = new JsonArray();
    Map<String, JsonObject> attractionById = index(attractions, "attractionId");
    Map<String, JsonObject> weatherByDate = index(weather, "date");
    Constraints constraints = new Constraints(request.pace().isBlank() ? "balanced" : request.pace(),
        Boolean.TRUE.equals(request.avoidEarlyMorning()), Boolean.TRUE.equals(request.elderlyTravel()));
    int activityLimit = switch (constraints.pace()) { case "relaxed" -> 240; case "intensive" -> 480; default -> 360; };
    if (constraints.elderlyTravel()) activityLimit = Math.min(activityLimit, 240);
    for (JsonElement dayElement : days) {
      if (!dayElement.isJsonObject()) continue;
      JsonObject day = dayElement.getAsJsonObject(); JsonArray activities = array(day, "activities"); int total = 0;
      List<TimedActivity> timedActivities = new ArrayList<>();
      for (int index = 0; index < activities.size(); index++) {
        // 模型偶发输出 activities 元素非对象（如字符串），跳过而非 getAsJsonObject 抛 CCE 击穿整个 run。
        if (!activities.get(index).isJsonObject()) continue;
        JsonObject activity = activities.get(index).getAsJsonObject();
        String attractionId = text(activity, "attractionId"); JsonObject fact = attractionById.get(attractionId);
        if (fact != null) mergeFacts(activity, fact);
        else if (!attractionId.isBlank()) conflict(conflicts, "UNVERIFIED_ATTRACTION", text(day, "date"), attractionId);
        int duration = integer(activity, "durationMinutes", 90); total += duration;
        if (index == 0 && constraints.avoidEarlyMorning() && before(text(activity, "startTime"), LocalTime.of(9, 0))) {
          activity.addProperty("startTime", "09:00"); activity.addProperty("timeAdjusted", true);
        }
        JsonObject forecast = weatherByDate.get(text(day, "date"));
        if (forecast != null && number(forecast, "precipitationProbability", 0) >= 60
            && !bool(activity, "indoor", false)) {
          conflict(conflicts, "RAIN_OUTDOOR_CONFLICT", text(day, "date"), text(activity, "title"));
        }
        int start = minutes(text(activity, "startTime"));
        if (start >= 0) timedActivities.add(new TimedActivity(start, duration, text(activity, "title")));
      }
      timedActivities.sort(Comparator.comparingInt(TimedActivity::startMinutes));
      for (int index = 1; index < timedActivities.size(); index++) {
        TimedActivity previous = timedActivities.get(index - 1); TimedActivity current = timedActivities.get(index);
        if (current.startMinutes() < previous.startMinutes() + previous.durationMinutes()) {
          conflict(conflicts, "ACTIVITY_TIME_OVERLAP", text(day, "date"), previous.title() + " / " + current.title());
        }
      }
      if (total > activityLimit) conflict(conflicts, "DAILY_ACTIVITY_LIMIT_EXCEEDED", text(day, "date"), total + ">" + activityLimit);
      if (constraints.elderlyTravel()) day.addProperty("restBufferMinutes", 60);
    }
    return new Result(days, conflicts);
  }

  private void mergeFacts(JsonObject activity, JsonObject fact) {
    for (String key : new String[]{"lat", "lng", "coordinateSystem", "openingHours", "requiresReservation", "sourceUrl"}) {
      if (fact.has(key)) activity.add(key, fact.get(key).deepCopy());
    }
    if (!activity.has("attractionName") || text(activity, "attractionName").isBlank()) activity.addProperty("attractionName", text(fact, "name"));
  }
  private Map<String, JsonObject> index(JsonArray values, String key) { Map<String, JsonObject> map = new HashMap<>(); for (JsonElement e : values) if (e.isJsonObject() && !text(e.getAsJsonObject(), key).isBlank()) map.put(text(e.getAsJsonObject(), key), e.getAsJsonObject()); return map; }
  private void conflict(JsonArray values, String code, String date, String detail) {
    JsonObject value = new JsonObject(); value.addProperty("code", code); value.addProperty("date", date); value.addProperty("detail", detail);
    value.addProperty("message", switch (code) {
      case "DAILY_ACTIVITY_LIMIT_EXCEEDED" -> "当天活动总时长超过当前旅行节奏上限，建议减少活动或增加休息时间。";
      case "RAIN_OUTDOOR_CONFLICT" -> "当天降雨概率较高，户外活动需要准备室内备选方案。";
        case "UNVERIFIED_ATTRACTION" -> "景点资料尚未通过外部数据源核实，请确认开放时间和预约要求。";
        case "ACTIVITY_TIME_OVERLAP" -> "同一天的活动时间重叠，需调整后才能写入日历。";
      default -> "该行程信息需要在出发前核实。";
    });
    values.add(value);
  }
  private boolean before(String value, LocalTime minimum) { try { return value.isBlank() || LocalTime.parse(value).isBefore(minimum); } catch (Exception e) { return true; } }
  private int minutes(String value) { try { LocalTime time = LocalTime.parse(value); return time.getHour() * 60 + time.getMinute(); } catch (Exception e) { return -1; } }
  private JsonArray array(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray(); }
  private String text(JsonObject value, String name) { JsonElement e = value.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
  private int integer(JsonObject value, String name, int fallback) { try { return value.get(name).getAsInt(); } catch (Exception e) { return fallback; } }
  private double number(JsonObject value, String name, double fallback) { try { return value.get(name).getAsDouble(); } catch (Exception e) { return fallback; } }
  private boolean bool(JsonObject value, String name, boolean fallback) { try { return value.get(name).getAsBoolean(); } catch (Exception e) { return fallback; } }
  private record TimedActivity(int startMinutes, int durationMinutes, String title) {}
}
