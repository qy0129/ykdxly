package com.changlu.planner.agent.subagents.scheduling;

import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 检查未来七天的时间重叠和用户可用时段。 */
public final class ConflictTool {
  private final Database database;

  public ConflictTool(Database database) { this.database = database; }

  public SchedulingResult inspect(Database.Context context) throws Exception {
    List<SchedulingResult.ScheduleSlot> schedules = schedules(context);
    Preference preference = preference(context);
    List<SchedulingResult.Conflict> conflicts = new ArrayList<>();
    appendOverlaps(schedules, conflicts);
    if (preference.configured()) appendPreferenceConflicts(schedules, preference, conflicts);
    return new SchedulingResult(preference.configured(), List.copyOf(schedules), List.copyOf(conflicts));
  }

  private List<SchedulingResult.ScheduleSlot> schedules(Database.Context context) throws Exception {
    String sql = "SELECT id,title,start_at,duration_minutes FROM schedule_items "
        + "WHERE workspace_id=? AND deleted_at IS NULL AND status<>'cancelled' "
        + "AND start_at>=NOW() AND start_at<DATE_ADD(CURDATE(),INTERVAL 8 DAY) ORDER BY start_at LIMIT 100";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        List<SchedulingResult.ScheduleSlot> rows = new ArrayList<>();
        while (rs.next()) rows.add(new SchedulingResult.ScheduleSlot(
            Database.id(rs, "id"), rs.getString("title"),
            rs.getTimestamp("start_at").toLocalDateTime(), rs.getInt("duration_minutes")));
        return rows;
      }
    }
  }

  private Preference preference(Database.Context context) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT availability,max_session_minutes FROM planning_preferences WHERE workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next() || rs.getString("availability") == null) return new Preference(false, new JsonObject(), 120);
        return new Preference(true, JsonParser.parseString(rs.getString("availability")).getAsJsonObject(),
            rs.getInt("max_session_minutes"));
      }
    }
  }

  private void appendOverlaps(List<SchedulingResult.ScheduleSlot> schedules,
                              List<SchedulingResult.Conflict> conflicts) {
    for (int left = 0; left < schedules.size(); left++) {
      SchedulingResult.ScheduleSlot first = schedules.get(left);
      LocalDateTime firstEnd = first.startAt().plusMinutes(first.durationMinutes());
      for (int right = left + 1; right < schedules.size(); right++) {
        SchedulingResult.ScheduleSlot second = schedules.get(right);
        if (!second.startAt().isBefore(firstEnd)) break;
        LocalDateTime secondEnd = second.startAt().plusMinutes(second.durationMinutes());
        if (first.startAt().isBefore(secondEnd)) {
          conflicts.add(new SchedulingResult.Conflict("overlap",
              "“" + first.title() + "”与“" + second.title() + "”时间重叠",
              List.of(first.id(), second.id())));
        }
      }
    }
  }

  private void appendPreferenceConflicts(List<SchedulingResult.ScheduleSlot> schedules, Preference preference,
                                         List<SchedulingResult.Conflict> conflicts) {
    for (SchedulingResult.ScheduleSlot schedule : schedules) {
      if (schedule.durationMinutes() > preference.maxSessionMinutes()) {
        conflicts.add(new SchedulingResult.Conflict("session_too_long",
            "“" + schedule.title() + "”时长超过单次上限 " + preference.maxSessionMinutes() + " 分钟",
            List.of(schedule.id())));
      }
      LocalDateTime end = schedule.startAt().plusMinutes(schedule.durationMinutes());
      if (!withinAvailability(preference.availability(), schedule.startAt(), end)) {
        conflicts.add(new SchedulingResult.Conflict("outside_availability",
            "“" + schedule.title() + "”不在已配置的可用时段内", List.of(schedule.id())));
      }
    }
  }

  private boolean withinAvailability(JsonObject availability, LocalDateTime start, LocalDateTime end) {
    String day = dayKey(start.getDayOfWeek());
    if (!availability.has(day) || !availability.get(day).isJsonArray()) return false;
    JsonArray slots = availability.getAsJsonArray(day);
    for (JsonElement element : slots) {
      JsonObject slot = element.getAsJsonObject();
      LocalTime from = LocalTime.parse(slot.get("start").getAsString());
      LocalTime to = LocalTime.parse(slot.get("end").getAsString());
      if (!start.toLocalTime().isBefore(from) && !end.toLocalTime().isAfter(to)
          && start.toLocalDate().equals(end.toLocalDate())) return true;
    }
    return false;
  }

  private String dayKey(DayOfWeek day) { return day.name().toLowerCase(Locale.ROOT); }
  private record Preference(boolean configured, JsonObject availability, int maxSessionMinutes) {}
}
