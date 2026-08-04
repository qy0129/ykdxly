package com.changlu.planner.agent.subagents.scheduling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.LocalDateTime;
import java.util.List;

/** 排期检查的结构化结果。 */
public record SchedulingResult(
    boolean preferenceConfigured,
    List<ScheduleSlot> schedules,
    List<Conflict> conflicts
) {
  public record ScheduleSlot(String id, String title, LocalDateTime startAt, int durationMinutes) {}
  public record Conflict(String type, String message, List<String> scheduleIds) {}

  public String message() {
    if (schedules.isEmpty()) return "未来七天没有需要检查的日程。";
    if (conflicts.isEmpty()) {
      return preferenceConfigured
          ? "未来七天共有 " + schedules.size() + " 项日程，暂未发现时间冲突或超出可用时段。"
          : "未来七天共有 " + schedules.size() + " 项日程，暂未发现重叠；尚未配置可用时段，无法检查时间偏好。";
    }
    StringBuilder value = new StringBuilder("发现 ").append(conflicts.size()).append(" 项排期问题：");
    for (Conflict conflict : conflicts) value.append("\n- ").append(conflict.message());
    value.append("\n需要调整时，我会先生成草案供你确认。");
    return value.toString();
  }

  public JsonObject toJson() {
    JsonArray scheduleRows = new JsonArray();
    for (ScheduleSlot schedule : schedules) {
      JsonObject row = new JsonObject();
      row.addProperty("id", schedule.id());
      row.addProperty("title", schedule.title());
      row.addProperty("startAt", schedule.startAt().toString());
      row.addProperty("durationMinutes", schedule.durationMinutes());
      scheduleRows.add(row);
    }
    JsonArray conflictRows = new JsonArray();
    for (Conflict conflict : conflicts) {
      JsonObject row = new JsonObject();
      row.addProperty("type", conflict.type());
      row.addProperty("message", conflict.message());
      JsonArray ids = new JsonArray();
      conflict.scheduleIds().forEach(ids::add);
      row.add("scheduleIds", ids);
      conflictRows.add(row);
    }
    JsonObject result = new JsonObject();
    result.addProperty("reply", message());
    JsonObject scheduling = new JsonObject();
    scheduling.addProperty("preferenceConfigured", preferenceConfigured);
    scheduling.add("schedules", scheduleRows);
    scheduling.add("conflicts", conflictRows);
    result.add("scheduling", scheduling);
    return result;
  }
}
