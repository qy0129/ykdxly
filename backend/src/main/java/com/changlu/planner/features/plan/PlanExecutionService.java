package com.changlu.planner.features.plan;

import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Deterministic plan execution rules shared by HTTP, AI and channel adapters. */
public final class PlanExecutionService {
  private final Database database;
  private final Gson gson = new Gson();

  public PlanExecutionService(Database database) { this.database = database; }

  public JsonArray listTasks(UUID planId, Database.Context context) throws SQLException {
    String sql = "SELECT t.*, COALESCE(occ.total_count,0) schedule_count, COALESCE(occ.done_count,0) completed_schedule_count "
        + "FROM plan_tasks t JOIN plans p ON p.id = t.plan_id "
        + "LEFT JOIN (SELECT task_id,COUNT(*) total_count,SUM(status='done') done_count FROM schedule_items "
        + "WHERE deleted_at IS NULL AND source_type='task_recurrence' AND status<>'cancelled' GROUP BY task_id) occ ON occ.task_id=t.id "
        + "WHERE t.plan_id = ? AND p.workspace_id = ? AND t.deleted_at IS NULL AND p.deleted_at IS NULL "
        + "ORDER BY t.stage_id, t.sort_order, t.created_at";
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) rows.add(taskRow(rs)); }
    }
    return rows;
  }

  public JsonArray listTrash(Database.Context context) throws SQLException {
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection()) {
      appendTrash(c, rows, "plan", "plans", context.workspaceId());
      appendTrash(c, rows, "todo", "todos", context.workspaceId());
      appendTrash(c, rows, "schedule", "schedule_items", context.workspaceId());
      try (PreparedStatement p = c.prepareStatement(
          "SELECT s.id, s.title, s.deleted_at, s.purge_after FROM plan_stages s JOIN plans p ON p.id = s.plan_id "
              + "WHERE p.workspace_id = ? AND s.deleted_at IS NOT NULL ORDER BY s.deleted_at DESC")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) rows.add(trashRow(rs, "stage")); }
      }
      try (PreparedStatement p = c.prepareStatement(
          "SELECT t.id, t.title, t.deleted_at, t.purge_after FROM plan_tasks t JOIN plans p ON p.id = t.plan_id "
              + "WHERE p.workspace_id = ? AND t.deleted_at IS NOT NULL ORDER BY t.deleted_at DESC")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        try (ResultSet rs = p.executeQuery()) { while (rs.next()) rows.add(trashRow(rs, "task")); }
      }
    }
    return rows;
  }

  public JsonObject createStage(Database.Context context, UUID planId, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = createStage(c, context, planId, fields, null, UUID.randomUUID(), source);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject createStage(Connection c, Database.Context context, UUID planId, JsonObject fields,
                                UUID draftId, UUID changeSetId, String source) throws SQLException {
    requirePlan(c, context.workspaceId(), planId);
    UUID id = UUID.randomUUID();
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO plan_stages (id, plan_id, title, description, due_date, sort_order) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(planId));
      p.setString(3, required(fields, "title")); p.setString(4, nullable(fields, "description"));
      p.setObject(5, date(fields, "dueDate")); p.setInt(6, intValue(fields, "sortOrder", nextStageOrder(c, planId)));
      p.executeUpdate();
    }
    recomputeProgress(c, planId);
    JsonObject after = stage(c, context.workspaceId(), id, false);
    record(c, context, draftId, changeSetId, "plan_stage", id, "create_stage", null, after,
        string(fields, "reason", "创建计划阶段"), source, null, after.get("version").getAsInt());
    return after;
  }

  public JsonObject updateStage(Connection c, Database.Context context, UUID stageId, JsonObject fields,
                                UUID draftId, UUID changeSetId, String source) throws SQLException {
    JsonObject before = stage(c, context.workspaceId(), stageId, false);
    int expected = intValue(fields, "expectedVersion", before.get("version").getAsInt());
    try (PreparedStatement p = c.prepareStatement(
        "UPDATE plan_stages s JOIN plans p ON p.id = s.plan_id SET s.title = COALESCE(?, s.title), "
            + "s.description = COALESCE(?, s.description), s.due_date = COALESCE(?, s.due_date), "
            + "s.status = COALESCE(?, s.status), s.sort_order = COALESCE(?, s.sort_order), s.version = s.version + 1 "
            + "WHERE s.id = ? AND s.version = ? AND s.deleted_at IS NULL AND p.workspace_id = ? AND p.deleted_at IS NULL")) {
      p.setString(1, nullable(fields, "title")); p.setString(2, nullable(fields, "description"));
      p.setObject(3, date(fields, "dueDate")); p.setString(4, nullable(fields, "status"));
      setInteger(p, 5, optionalInt(fields, "sortOrder")); p.setBytes(6, Database.uuidBytes(stageId));
      p.setInt(7, expected); p.setBytes(8, Database.uuidBytes(context.workspaceId()));
      if (p.executeUpdate() == 0) throw new IllegalStateException("stage_version_conflict");
    }
    JsonObject after = stage(c, context.workspaceId(), stageId, false);
    record(c, context, draftId, changeSetId, "plan_stage", stageId, "update_stage", before, after,
        string(fields, "reason", "调整计划阶段"), source, null, after.get("version").getAsInt());
    return after;
  }

  public JsonObject updateStage(Database.Context context, UUID stageId, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = updateStage(c, context, stageId, fields, null, UUID.randomUUID(), source);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject softDeleteStage(Database.Context context, UUID stageId, int expectedVersion, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject before = stage(c, context.workspaceId(), stageId, false);
        UUID planId = UUID.fromString(before.get("planId").getAsString());
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE plan_stages s JOIN plans p ON p.id=s.plan_id SET s.deleted_at=NOW(),s.purge_after=DATE_ADD(NOW(),INTERVAL 30 DAY),s.version=s.version+1 "
                + "WHERE s.id=? AND s.version=? AND s.deleted_at IS NULL AND p.workspace_id=?")) {
          p.setBytes(1, Database.uuidBytes(stageId)); p.setInt(2, expectedVersion); p.setBytes(3, Database.uuidBytes(context.workspaceId()));
          if (p.executeUpdate() == 0) throw new IllegalStateException("stage_version_conflict");
        }
        recomputeProgress(c, planId); JsonObject after = stage(c, context.workspaceId(), stageId, true);
        record(c, context, null, UUID.randomUUID(), "plan_stage", stageId, "delete_stage", before, after, "移入回收站", source, null, after.get("version").getAsInt());
        c.commit(); return after;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject createTask(Database.Context context, UUID planId, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = createTask(c, context, planId, fields, null, UUID.randomUUID(), source);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject createTask(Connection c, Database.Context context, UUID planId, JsonObject fields,
                               UUID draftId, UUID changeSetId, String source) throws SQLException {
    UUID stageId = UUID.fromString(required(fields, "stageId"));
    requireStage(c, context.workspaceId(), planId, stageId);
    String recurrenceType = string(fields, "recurrenceType", "once");
    LocalDate scheduleStart = localDate(fields, "scheduleStartDate");
    LocalDate recurrenceEnd = localDate(fields, "recurrenceEndDate");
    LocalTime scheduledTime = localTime(fields, "scheduledTime");
    if (scheduleStart != null || recurrenceEnd != null || scheduledTime != null || fields.has("recurrenceType")) {
      if (scheduledTime == null) throw new IllegalArgumentException("请选择执行时间");
      TaskRecurrence.dates(recurrenceType, scheduleStart, recurrenceEnd);
      if ("once".equals(recurrenceType)) recurrenceEnd = scheduleStart;
    }
    UUID id = UUID.randomUUID();
    String sql = "INSERT INTO plan_tasks (id, plan_id, stage_id, title, description, status, priority, estimated_minutes, due_at, "
        + "recurrence_type, schedule_start_date, recurrence_end_date, scheduled_time, sort_order) "
        + "VALUES (?, ?, ?, ?, ?, 'pending', ?, ?, ?, ?, ?, ?, ?, ?)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(planId)); p.setBytes(3, Database.uuidBytes(stageId));
      p.setString(4, required(fields, "title")); p.setString(5, nullable(fields, "description")); p.setString(6, priority(fields));
      setInteger(p, 7, optionalInt(fields, "estimatedMinutes")); p.setObject(8, timestamp(fields, "dueAt"));
      p.setString(9, recurrenceType); p.setObject(10, sqlDate(scheduleStart)); p.setObject(11, sqlDate(recurrenceEnd));
      p.setObject(12, scheduledTime == null ? null : java.sql.Time.valueOf(scheduledTime)); p.setInt(13, intValue(fields, "sortOrder", 0));
      p.executeUpdate();
    }
    if (scheduleStart != null) {
      replacePendingTaskSchedules(c, context, id, planId, stageId, required(fields, "title"),
          intValue(fields, "estimatedMinutes", 60), recurrenceType, scheduleStart, recurrenceEnd, scheduledTime,
          draftId, changeSetId);
    }
    recomputeProgress(c, planId);
    JsonObject after = task(c, context.workspaceId(), id, false);
    record(c, context, draftId, changeSetId, "plan_task", id, "create_task", null, after,
        string(fields, "reason", "创建计划任务"), source, null, after.get("version").getAsInt());
    return after;
  }

  public JsonObject updateTask(Database.Context context, UUID taskId, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = updateTask(c, context, taskId, fields, null, UUID.randomUUID(), source);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject updateTask(Connection c, Database.Context context, UUID taskId, JsonObject fields,
                               UUID draftId, UUID changeSetId, String source) throws SQLException {
    JsonObject before = task(c, context.workspaceId(), taskId, false);
    int expected = intValue(fields, "expectedVersion", before.get("version").getAsInt());
    String status = nullable(fields, "status");
    TaskStateRules.validate(before.get("status").getAsString(), status, string(fields, "actionType", ""), nullable(fields, "reason"), nullable(fields, "dueAt"));
    if ("done".equals(before.get("status").getAsString()) && "done".equals(status)) return before;
    String sql = "UPDATE plan_tasks SET title = COALESCE(?, title), description = COALESCE(?, description), "
        + "status = COALESCE(?, status), priority = COALESCE(?, priority), estimated_minutes = COALESCE(?, estimated_minutes), "
        + "actual_minutes = COALESCE(?, actual_minutes), due_at = COALESCE(?, due_at), recurrence_type = COALESCE(?, recurrence_type), "
        + "schedule_start_date = COALESCE(?, schedule_start_date), recurrence_end_date = COALESCE(?, recurrence_end_date), scheduled_time = COALESCE(?, scheduled_time), "
        + "blocked_reason = CASE WHEN ? = 'blocked' THEN ? WHEN ? IS NOT NULL AND ? <> 'blocked' THEN NULL ELSE blocked_reason END, "
        + "completed_at = CASE WHEN ? = 'done' THEN NOW() WHEN ? IS NOT NULL AND ? <> 'done' THEN NULL ELSE completed_at END, version = version + 1 "
        + "WHERE id = ? AND version = ? AND deleted_at IS NULL AND plan_id IN (SELECT id FROM plans WHERE workspace_id = ? AND deleted_at IS NULL)";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, nullable(fields, "title")); p.setString(2, nullable(fields, "description")); p.setString(3, status);
      p.setString(4, nullable(fields, "priority")); setInteger(p, 5, optionalInt(fields, "estimatedMinutes")); setInteger(p, 6, optionalInt(fields, "actualMinutes"));
      p.setObject(7, timestamp(fields, "dueAt")); p.setString(8, nullable(fields, "recurrenceType"));
      p.setObject(9, sqlDate(localDate(fields, "scheduleStartDate"))); p.setObject(10, sqlDate(localDate(fields, "recurrenceEndDate")));
      LocalTime requestedTime = localTime(fields, "scheduledTime");
      p.setObject(11, requestedTime == null ? null : java.sql.Time.valueOf(requestedTime));
      p.setString(12, status); p.setString(13, nullable(fields, "reason")); p.setString(14, status); p.setString(15, status);
      p.setString(16, status); p.setString(17, status); p.setString(18, status);
      p.setBytes(19, Database.uuidBytes(taskId)); p.setInt(20, expected); p.setBytes(21, Database.uuidBytes(context.workspaceId()));
      if (p.executeUpdate() == 0) throw new IllegalStateException("task_version_conflict");
    }
    JsonObject after = task(c, context.workspaceId(), taskId, false);
    if (hasSchedulingFields(fields)) {
      String recurrenceType = string(after, "recurrenceType", "once");
      LocalDate scheduleStart = localDate(after, "scheduleStartDate");
      LocalDate recurrenceEnd = localDate(after, "recurrenceEndDate");
      LocalTime scheduledTime = localTime(after, "scheduledTime");
      if (scheduledTime == null) throw new IllegalArgumentException("请选择执行时间");
      TaskRecurrence.dates(recurrenceType, scheduleStart, recurrenceEnd);
      replacePendingTaskSchedules(c, context, taskId,
          UUID.fromString(after.get("planId").getAsString()), UUID.fromString(after.get("stageId").getAsString()),
          after.get("title").getAsString(), after.get("estimatedMinutes").isJsonNull() ? 60 : after.get("estimatedMinutes").getAsInt(),
          recurrenceType, scheduleStart, recurrenceEnd, scheduledTime, draftId, changeSetId);
      syncLinkedTask(c, context.workspaceId(), taskId);
      after = task(c, context.workspaceId(), taskId, false);
    }
    UUID planId = UUID.fromString(after.get("planId").getAsString());
    String action = string(fields, "actionType", status == null ? "update_task" : status + "_task");
    record(c, context, draftId, changeSetId, "plan_task", taskId, action, before, after,
        string(fields, "reason", action), source, optionalInt(fields, "actualMinutes"), after.get("version").getAsInt());
    recomputeProgress(c, planId);
    return after;
  }

  public JsonObject softDeleteTask(Database.Context context, UUID taskId, int expectedVersion, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject before = task(c, context.workspaceId(), taskId, false);
        try (PreparedStatement p = c.prepareStatement("UPDATE plan_tasks SET deleted_at = NOW(), purge_after = DATE_ADD(NOW(), INTERVAL 30 DAY), version = version + 1 WHERE id = ? AND version = ? AND deleted_at IS NULL AND plan_id IN (SELECT id FROM plans WHERE workspace_id = ?)")) {
          p.setBytes(1, Database.uuidBytes(taskId)); p.setInt(2, expectedVersion); p.setBytes(3, Database.uuidBytes(context.workspaceId()));
          if (p.executeUpdate() == 0) throw new IllegalStateException("task_version_conflict");
        }
        // 未完成的自动安排和任务一起进入回收站，已完成记录继续保留用于复盘。
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE schedule_items SET deleted_at=NOW(),purge_after=DATE_ADD(NOW(),INTERVAL 30 DAY),version=version+1 "
                + "WHERE task_id=? AND source_type='task_recurrence' AND status<>'done' AND deleted_at IS NULL")) {
          p.setBytes(1, Database.uuidBytes(taskId)); p.executeUpdate();
        }
        JsonObject after = task(c, context.workspaceId(), taskId, true);
        record(c, context, null, UUID.randomUUID(), "plan_task", taskId, "delete_task", before, after, "移入回收站", source, null, after.get("version").getAsInt());
        recomputeProgress(c, UUID.fromString(before.get("planId").getAsString())); c.commit(); return after;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject restoreTask(Database.Context context, UUID taskId, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject before = task(c, context.workspaceId(), taskId, true);
        try (PreparedStatement p = c.prepareStatement("UPDATE plan_tasks SET deleted_at = NULL, purge_after = NULL, version = version + 1 WHERE id = ? AND deleted_at IS NOT NULL AND plan_id IN (SELECT id FROM plans WHERE workspace_id = ?)")) {
          p.setBytes(1, Database.uuidBytes(taskId)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
          if (p.executeUpdate() == 0) throw new IllegalArgumentException("task_not_in_trash");
        }
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE schedule_items SET deleted_at=NULL,purge_after=NULL,version=version+1 "
                + "WHERE task_id=? AND source_type='task_recurrence' AND deleted_at IS NOT NULL")) {
          p.setBytes(1, Database.uuidBytes(taskId)); p.executeUpdate();
        }
        JsonObject after = task(c, context.workspaceId(), taskId, false);
        record(c, context, null, UUID.randomUUID(), "plan_task", taskId, "restore_task", before, after, "从回收站恢复", source, null, after.get("version").getAsInt());
        recomputeProgress(c, UUID.fromString(after.get("planId").getAsString())); c.commit(); return after;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject createSchedule(Connection c, Database.Context context, JsonObject fields,
                                   UUID draftId, UUID changeSetId, String source,
                                   boolean requireAvailability) throws SQLException {
    LocalDateTime start = LocalDateTime.parse(required(fields, "startAt"));
    int duration = intValue(fields, "durationMinutes", 30);
    if (duration < 1) throw new IllegalArgumentException("duration_minutes_invalid");
    validateSchedule(c, context, start, duration, null, requireAvailability);
    UUID planId = uuid(fields, "planId"); UUID stageId = uuid(fields, "stageId"); UUID taskId = uuid(fields, "taskId");
    if (taskId != null) {
      JsonObject task = task(c, context.workspaceId(), taskId, false);
      UUID taskPlan = UUID.fromString(task.get("planId").getAsString());
      UUID taskStage = UUID.fromString(task.get("stageId").getAsString());
      if (planId != null && !planId.equals(taskPlan)) throw new IllegalArgumentException("schedule_plan_mismatch");
      if (stageId != null && !stageId.equals(taskStage)) throw new IllegalArgumentException("schedule_stage_mismatch");
      planId = taskPlan; stageId = taskStage;
    } else if (planId != null) {
      requirePlan(c, context.workspaceId(), planId);
      if (stageId != null) requireStage(c, context.workspaceId(), planId, stageId);
    }
    UUID id = UUID.randomUUID();
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO schedule_items (id, workspace_id, plan_id, stage_id, task_id, created_by, title, description, start_at, duration_minutes, source_type) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, bytes(planId)); p.setBytes(4, bytes(stageId)); p.setBytes(5, bytes(taskId));
      p.setBytes(6, Database.uuidBytes(context.userId())); p.setString(7, required(fields, "title"));
      p.setString(8, nullable(fields, "description")); p.setTimestamp(9, Timestamp.valueOf(start));
      p.setInt(10, duration); p.setString(11, source); p.executeUpdate();
    }
    JsonObject after = schedule(c, context.workspaceId(), id, false);
    record(c, context, draftId, changeSetId, "schedule", id, "create_schedule", null, after,
        string(fields, "reason", "创建日程时间块"), source, null, after.get("version").getAsInt());
    return after;
  }

  public JsonObject createSchedule(Database.Context context, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = createSchedule(c, context, fields, null, UUID.randomUUID(), source, false);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject updateSchedule(Connection c, Database.Context context, UUID scheduleId, JsonObject fields,
                                   UUID draftId, UUID changeSetId, String source,
                                   boolean requireAvailability) throws SQLException {
    JsonObject before = schedule(c, context.workspaceId(), scheduleId, false);
    int expected = intValue(fields, "expectedVersion", before.get("version").getAsInt());
    LocalDateTime start = fields.has("startAt") ? LocalDateTime.parse(required(fields, "startAt"))
        : LocalDateTime.parse(before.get("startAt").getAsString());
    int duration = intValue(fields, "durationMinutes", before.get("durationMinutes").getAsInt());
    if (fields.has("startAt") || fields.has("durationMinutes")) {
      validateSchedule(c, context, start, duration, scheduleId, requireAvailability);
    }
    String status = nullable(fields, "status");
    try (PreparedStatement p = c.prepareStatement(
        "UPDATE schedule_items SET title = COALESCE(?, title), description = COALESCE(?, description), "
            + "start_at = ?, duration_minutes = ?, status = COALESCE(?, status), "
            + "completed_at = CASE WHEN ? = 'done' AND completed_at IS NULL THEN NOW() WHEN ? IS NOT NULL AND ? <> 'done' THEN NULL ELSE completed_at END, "
            + "version = version + 1 WHERE id = ? AND version = ? AND workspace_id = ? AND deleted_at IS NULL")) {
      p.setString(1, nullable(fields, "title")); p.setString(2, nullable(fields, "description"));
      p.setTimestamp(3, Timestamp.valueOf(start)); p.setInt(4, duration); p.setString(5, status);
      p.setString(6, status); p.setString(7, status); p.setString(8, status);
      p.setBytes(9, Database.uuidBytes(scheduleId)); p.setInt(10, expected); p.setBytes(11, Database.uuidBytes(context.workspaceId()));
      if (p.executeUpdate() == 0) throw new IllegalStateException("schedule_version_conflict");
    }
    JsonObject after = schedule(c, context.workspaceId(), scheduleId, false);
    if (status != null && after.has("taskId") && !after.get("taskId").isJsonNull()) {
      syncLinkedTask(c, context.workspaceId(), UUID.fromString(after.get("taskId").getAsString()));
    }
    String action = string(fields, "actionType", status == null ? "update_schedule" : status + "_schedule");
    record(c, context, draftId, changeSetId, "schedule", scheduleId, action, before, after,
        string(fields, "reason", action), source, optionalInt(fields, "actualMinutes"), after.get("version").getAsInt());
    return after;
  }

  public JsonObject updateSchedule(Database.Context context, UUID scheduleId, JsonObject fields, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject result = updateSchedule(c, context, scheduleId, fields, null, UUID.randomUUID(), source, false);
        c.commit(); return result;
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  /** 恢复阶段时保留其原有任务，并重新计算所属计划的真实任务进度。 */
  public JsonObject restoreStage(Database.Context context, UUID stageId, String source) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        JsonObject before = stage(c, context.workspaceId(), stageId, true);
        UUID planId = UUID.fromString(before.get("planId").getAsString());
        try (PreparedStatement p = c.prepareStatement(
            "UPDATE plan_stages s JOIN plans p ON p.id=s.plan_id "
                + "SET s.deleted_at=NULL,s.purge_after=NULL,s.version=s.version+1 "
                + "WHERE s.id=? AND s.deleted_at IS NOT NULL AND p.workspace_id=? AND p.deleted_at IS NULL")) {
          p.setBytes(1, Database.uuidBytes(stageId));
          p.setBytes(2, Database.uuidBytes(context.workspaceId()));
          if (p.executeUpdate() == 0) throw new IllegalArgumentException("stage_not_in_trash");
        }
        JsonObject after = stage(c, context.workspaceId(), stageId, false);
        record(c, context, null, UUID.randomUUID(), "plan_stage", stageId, "restore_stage",
            before, after, "从回收站恢复", source, null, after.get("version").getAsInt());
        recomputeProgress(c, planId);
        c.commit();
        return after;
      } catch (Exception error) {
        c.rollback();
        throw error;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  /** 已完成安排保留为事实记录，只替换尚未执行的自动安排。 */
  private void replacePendingTaskSchedules(Connection c, Database.Context context, UUID taskId, UUID planId,
                                           UUID stageId, String title, int durationMinutes, String recurrenceType,
                                           LocalDate scheduleStart, LocalDate recurrenceEnd, LocalTime scheduledTime,
                                           UUID draftId, UUID changeSetId) throws SQLException {
    List<LocalDate> dates = TaskRecurrence.dates(recurrenceType, scheduleStart, recurrenceEnd);
    Set<LocalDate> completedDates = new HashSet<>();
    try (PreparedStatement p = c.prepareStatement(
        "SELECT DATE(start_at) completed_date FROM schedule_items WHERE task_id=? "
            + "AND source_type='task_recurrence' AND status='done' AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(taskId));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) completedDates.add(rs.getDate("completed_date").toLocalDate());
      }
    }
    try (PreparedStatement p = c.prepareStatement(
        "DELETE FROM schedule_items WHERE task_id=? AND source_type='task_recurrence' AND status<>'done'")) {
      p.setBytes(1, Database.uuidBytes(taskId)); p.executeUpdate();
    }
    for (LocalDate date : dates) {
      if (completedDates.contains(date)) continue;
      JsonObject schedule = new JsonObject();
      schedule.addProperty("title", title);
      schedule.addProperty("startAt", date.atTime(scheduledTime).toString());
      schedule.addProperty("durationMinutes", Math.max(1, durationMinutes));
      schedule.addProperty("planId", planId.toString());
      schedule.addProperty("stageId", stageId.toString());
      schedule.addProperty("taskId", taskId.toString());
      schedule.addProperty("reason", "按任务频率自动排期");
      createSchedule(c, context, schedule, draftId, changeSetId, "task_recurrence", false);
    }
  }

  private void syncLinkedTask(Connection c, UUID workspaceId, UUID taskId) throws SQLException {
    String summarySql = "SELECT t.plan_id,COUNT(s.id) total_count,COALESCE(SUM(s.status='done'),0) done_count "
        + "FROM plan_tasks t JOIN plans p ON p.id=t.plan_id LEFT JOIN schedule_items s ON s.task_id=t.id "
        + "AND s.source_type='task_recurrence' AND s.deleted_at IS NULL AND s.status<>'cancelled' "
        + "WHERE t.id=? AND p.workspace_id=? AND t.deleted_at IS NULL GROUP BY t.plan_id";
    try (PreparedStatement summary = c.prepareStatement(summarySql)) {
      summary.setBytes(1, Database.uuidBytes(taskId)); summary.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = summary.executeQuery()) {
        if (!rs.next() || rs.getInt("total_count") == 0) return;
        int total = rs.getInt("total_count"), done = rs.getInt("done_count");
        String status = done == total ? "done" : done > 0 ? "in_progress" : "pending";
        try (PreparedStatement update = c.prepareStatement(
            "UPDATE plan_tasks SET status=?,completed_at=CASE WHEN ?='done' THEN COALESCE(completed_at,NOW()) ELSE NULL END,version=version+1 WHERE id=?")) {
          update.setString(1, status); update.setString(2, status); update.setBytes(3, Database.uuidBytes(taskId)); update.executeUpdate();
        }
        recomputeProgress(c, Database.bytesUuid(rs.getBytes("plan_id")));
      }
    }
  }

  public JsonObject preference(Database.Context context) throws SQLException {
    try (Connection c = database.connection()) { return preference(c, context); }
  }

  public JsonObject preference(Connection c, Database.Context context) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(
        "SELECT timezone, availability, max_session_minutes, buffer_minutes FROM planning_preferences WHERE workspace_id = ? AND user_id = ?")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        JsonObject result = new JsonObject();
        if (!rs.next()) { result.addProperty("configured", false); result.addProperty("timezone", "Asia/Shanghai"); return result; }
        result.addProperty("configured", rs.getString("availability") != null); result.addProperty("timezone", rs.getString("timezone"));
        result.add("availability", rs.getString("availability") == null ? null : JsonParser.parseString(rs.getString("availability")));
        result.addProperty("maxSessionMinutes", rs.getInt("max_session_minutes")); result.addProperty("bufferMinutes", rs.getInt("buffer_minutes")); return result;
      }
    }
  }

  public JsonObject savePreference(Database.Context context, JsonObject fields) throws SQLException {
    try (Connection c = database.connection()) { return savePreference(c, context, fields); }
  }

  public JsonObject savePreference(Connection c, Database.Context context, JsonObject fields) throws SQLException {
    String timezone = string(fields, "timezone", "Asia/Shanghai");
    String availability = fields.has("availability") && !fields.get("availability").isJsonNull() ? gson.toJson(fields.get("availability")) : null;
    if (availability != null) validateAvailability(fields.getAsJsonObject("availability"));
    try (PreparedStatement p = c.prepareStatement(
        "INSERT INTO planning_preferences (workspace_id, user_id, timezone, availability, max_session_minutes, buffer_minutes) VALUES (?, ?, ?, ?, ?, ?) "
            + "ON DUPLICATE KEY UPDATE timezone = VALUES(timezone), availability = VALUES(availability), max_session_minutes = VALUES(max_session_minutes), buffer_minutes = VALUES(buffer_minutes)")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, timezone); p.setString(4, availability);
      p.setInt(5, intValue(fields, "maxSessionMinutes", 120)); p.setInt(6, intValue(fields, "bufferMinutes", 15)); p.executeUpdate();
    }
    return preference(c, context);
  }

  public void validateSchedule(Connection c, Database.Context context, LocalDateTime start, int durationMinutes,
                               UUID excludedSchedule, boolean requireAvailability) throws SQLException {
    LocalDateTime end = start.plusMinutes(durationMinutes);
    String conflictSql = "SELECT id, title FROM schedule_items WHERE workspace_id = ? AND deleted_at IS NULL AND status <> 'cancelled' "
        + "AND start_at < ? AND DATE_ADD(start_at, INTERVAL duration_minutes MINUTE) > ?" + (excludedSchedule == null ? "" : " AND id <> ?") + " LIMIT 1";
    try (PreparedStatement p = c.prepareStatement(conflictSql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setTimestamp(2, Timestamp.valueOf(end)); p.setTimestamp(3, Timestamp.valueOf(start));
      if (excludedSchedule != null) p.setBytes(4, Database.uuidBytes(excludedSchedule));
      try (ResultSet rs = p.executeQuery()) { if (rs.next()) throw new IllegalArgumentException("schedule_conflict:" + rs.getString("title")); }
    }
    JsonObject preference = preference(c, context);
    if (!preference.get("configured").getAsBoolean()) {
      if (requireAvailability) throw new IllegalArgumentException("availability_required");
      return;
    }
    if (durationMinutes > preference.get("maxSessionMinutes").getAsInt()) throw new IllegalArgumentException("session_too_long");
    if (!withinAvailability(preference.getAsJsonObject("availability"), start, end)) throw new IllegalArgumentException("outside_availability");
  }

  public void recomputeProgress(Connection c, UUID planId) throws SQLException {
    String occurrenceJoin = " LEFT JOIN (SELECT task_id,COUNT(*) total_count,SUM(status='done') done_count FROM schedule_items "
        + "WHERE deleted_at IS NULL AND source_type='task_recurrence' AND status<>'cancelled' GROUP BY task_id) occ ON occ.task_id=t.id ";
    String completedUnits = "CASE WHEN t.status='done' THEN IF(COALESCE(occ.total_count,0)>0,occ.total_count,1) "
        + "WHEN COALESCE(occ.total_count,0)>0 THEN occ.done_count ELSE 0 END";
    String totalUnits = "CASE WHEN COALESCE(occ.total_count,0)>0 THEN occ.total_count ELSE 1 END";
    String stageSql = "UPDATE plan_stages s LEFT JOIN (SELECT t.stage_id, "
        + "100 * SUM(" + completedUnits + ") / NULLIF(SUM(" + totalUnits + "), 0) task_pct, "
        + "100 * SUM((" + completedUnits + ") * COALESCE(t.estimated_minutes,0)) "
        + "/ NULLIF(SUM((" + totalUnits + ") * COALESCE(t.estimated_minutes,0)), 0) effort_pct "
        + "FROM plan_tasks t JOIN plan_stages active_stage ON active_stage.id=t.stage_id AND active_stage.deleted_at IS NULL "
        + occurrenceJoin
        + "WHERE t.plan_id = ? AND t.deleted_at IS NULL AND t.status <> 'cancelled' GROUP BY t.stage_id) x ON x.stage_id = s.id "
        + "SET s.task_progress = COALESCE(x.task_pct, 0), s.effort_progress = COALESCE(x.effort_pct, 0), s.progress = COALESCE(x.task_pct, 0) "
        + "WHERE s.plan_id = ? AND s.deleted_at IS NULL AND EXISTS (SELECT 1 FROM plan_tasks history WHERE history.stage_id=s.id)";
    try (PreparedStatement p = c.prepareStatement(stageSql)) { p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(planId)); p.executeUpdate(); }
    String planSql = "UPDATE plans p LEFT JOIN (SELECT t.plan_id, "
        + "100 * SUM(" + completedUnits + ") / NULLIF(SUM(" + totalUnits + "), 0) task_pct, "
        + "100 * SUM((" + completedUnits + ") * COALESCE(t.estimated_minutes,0)) "
        + "/ NULLIF(SUM((" + totalUnits + ") * COALESCE(t.estimated_minutes,0)), 0) effort_pct "
        + "FROM plan_tasks t JOIN plan_stages active_stage ON active_stage.id=t.stage_id AND active_stage.deleted_at IS NULL "
        + occurrenceJoin
        + "WHERE t.plan_id = ? AND t.deleted_at IS NULL AND t.status <> 'cancelled' GROUP BY t.plan_id) x ON x.plan_id = p.id "
        + "SET p.task_progress = COALESCE(x.task_pct, 0), p.effort_progress = COALESCE(x.effort_pct, 0), p.progress = COALESCE(x.task_pct, 0) "
        + "WHERE p.id = ? AND p.deleted_at IS NULL AND EXISTS (SELECT 1 FROM plan_tasks history WHERE history.plan_id=p.id)";
    try (PreparedStatement p = c.prepareStatement(planSql)) { p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(planId)); p.executeUpdate(); }
  }

  private JsonObject task(Connection c, UUID workspaceId, UUID taskId, boolean includeDeleted) throws SQLException {
    String sql = "SELECT t.*,COALESCE(occ.total_count,0) schedule_count,COALESCE(occ.done_count,0) completed_schedule_count "
        + "FROM plan_tasks t JOIN plans p ON p.id=t.plan_id "
        + "LEFT JOIN (SELECT task_id,COUNT(*) total_count,SUM(status='done') done_count FROM schedule_items "
        + "WHERE deleted_at IS NULL AND source_type='task_recurrence' AND status<>'cancelled' GROUP BY task_id) occ ON occ.task_id=t.id "
        + "WHERE t.id = ? AND p.workspace_id = ?" + (includeDeleted ? "" : " AND t.deleted_at IS NULL AND p.deleted_at IS NULL");
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(taskId)); p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("task_not_found"); return taskRow(rs); }
    }
  }

  private JsonObject stage(Connection c, UUID workspaceId, UUID stageId, boolean includeDeleted) throws SQLException {
    String sql = "SELECT s.* FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.id = ? AND p.workspace_id = ?"
        + (includeDeleted ? "" : " AND s.deleted_at IS NULL AND p.deleted_at IS NULL");
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(stageId)); p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("stage_not_found");
        JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id"));
        row.addProperty("planId", Database.id(rs, "plan_id")); row.addProperty("title", rs.getString("title"));
        row.addProperty("description", rs.getString("description")); row.addProperty("status", rs.getString("status"));
        row.addProperty("progress", rs.getDouble("progress")); row.addProperty("taskProgress", rs.getDouble("task_progress"));
        row.addProperty("effortProgress", rs.getDouble("effort_progress"));
        row.addProperty("dueDate", rs.getDate("due_date") == null ? null : rs.getDate("due_date").toString());
        row.addProperty("sortOrder", rs.getInt("sort_order")); row.addProperty("version", rs.getInt("version"));
        row.addProperty("deletedAt", rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toLocalDateTime().toString());
        return row;
      }
    }
  }

  private JsonObject schedule(Connection c, UUID workspaceId, UUID scheduleId, boolean includeDeleted) throws SQLException {
    String sql = "SELECT * FROM schedule_items WHERE id = ? AND workspace_id = ?" + (includeDeleted ? "" : " AND deleted_at IS NULL");
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(scheduleId)); p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("schedule_not_found");
        JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("title", rs.getString("title"));
        row.addProperty("description", rs.getString("description")); row.addProperty("status", rs.getString("status"));
        row.addProperty("startAt", rs.getTimestamp("start_at").toLocalDateTime().toString()); row.addProperty("durationMinutes", rs.getInt("duration_minutes"));
        addUuid(row, "planId", rs.getBytes("plan_id")); addUuid(row, "stageId", rs.getBytes("stage_id")); addUuid(row, "taskId", rs.getBytes("task_id"));
        row.addProperty("version", rs.getInt("version"));
        row.addProperty("deletedAt", rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toLocalDateTime().toString());
        return row;
      }
    }
  }

  private JsonObject taskRow(ResultSet rs) throws SQLException {
    JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("planId", Database.id(rs, "plan_id")); row.addProperty("stageId", Database.id(rs, "stage_id"));
    row.addProperty("title", rs.getString("title")); row.addProperty("description", rs.getString("description")); row.addProperty("status", rs.getString("status")); row.addProperty("priority", rs.getString("priority"));
    row.addProperty("estimatedMinutes", (Number) rs.getObject("estimated_minutes")); row.addProperty("actualMinutes", (Number) rs.getObject("actual_minutes"));
    row.addProperty("dueAt", rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toLocalDateTime().toString());
    row.addProperty("recurrenceType", rs.getString("recurrence_type"));
    row.addProperty("scheduleStartDate", rs.getDate("schedule_start_date") == null ? null : rs.getDate("schedule_start_date").toString());
    row.addProperty("recurrenceEndDate", rs.getDate("recurrence_end_date") == null ? null : rs.getDate("recurrence_end_date").toString());
    row.addProperty("scheduledTime", rs.getTime("scheduled_time") == null ? null : rs.getTime("scheduled_time").toLocalTime().toString());
    int scheduleCount = rs.getInt("schedule_count"), completedScheduleCount = rs.getInt("completed_schedule_count");
    row.addProperty("scheduleCount", scheduleCount); row.addProperty("completedScheduleCount", completedScheduleCount);
    row.addProperty("scheduleProgress", scheduleCount == 0 ? ("done".equals(rs.getString("status")) ? 100 : 0)
        : Math.round(100.0 * completedScheduleCount / scheduleCount));
    row.addProperty("completedAt", rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime().toString());
    row.addProperty("reason", rs.getString("blocked_reason")); row.addProperty("sortOrder", rs.getInt("sort_order")); row.addProperty("version", rs.getInt("version"));
    row.addProperty("deletedAt", rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toLocalDateTime().toString()); return row;
  }

  private void requireStage(Connection c, UUID workspaceId, UUID planId, UUID stageId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.id = ? AND s.plan_id = ? AND p.workspace_id = ? AND s.deleted_at IS NULL AND p.deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(stageId)); p.setBytes(2, Database.uuidBytes(planId)); p.setBytes(3, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("stage_not_found"); }
    }
  }

  private void requirePlan(Connection c, UUID workspaceId, UUID planId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM plans WHERE id = ? AND workspace_id = ? AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("plan_not_found"); }
    }
  }

  private int nextStageOrder(Connection c, UUID planId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM plan_stages WHERE plan_id = ? AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(planId)); try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); }
    }
  }

  private void appendTrash(Connection c, JsonArray rows, String type, String table, UUID workspaceId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(
        "SELECT id, title, deleted_at, purge_after FROM " + table + " WHERE workspace_id = ? AND deleted_at IS NOT NULL ORDER BY deleted_at DESC")) {
      p.setBytes(1, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) rows.add(trashRow(rs, type)); }
    }
  }

  private JsonObject trashRow(ResultSet rs, String type) throws SQLException {
    JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("type", type);
    row.addProperty("title", rs.getString("title")); row.addProperty("deletedAt", rs.getTimestamp("deleted_at").toLocalDateTime().toString());
    row.addProperty("purgeAfter", rs.getTimestamp("purge_after") == null ? null : rs.getTimestamp("purge_after").toLocalDateTime().toString());
    return row;
  }

  private void record(Connection c, Database.Context context, UUID draftId, UUID changeSetId, String entityType,
                      UUID entityId, String action, JsonObject before, JsonObject after, String reason,
                      String source, Integer actualMinutes, Integer versionAfter) throws SQLException {
    String sql = "INSERT INTO execution_records (id, workspace_id, user_id, draft_id, change_set_id, entity_type, entity_id, action_type, before_snapshot, after_snapshot, reason, source_channel, note, actual_minutes, version_after, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      p.setBytes(4, draftId == null ? null : Database.uuidBytes(draftId)); p.setBytes(5, changeSetId == null ? null : Database.uuidBytes(changeSetId)); p.setString(6, entityType); p.setBytes(7, Database.uuidBytes(entityId)); p.setString(8, action);
      p.setString(9, before == null ? null : gson.toJson(before)); p.setString(10, after == null ? null : gson.toJson(after)); p.setString(11, reason); p.setString(12, source); p.setString(13, reason);
      setInteger(p, 14, actualMinutes); setInteger(p, 15, versionAfter); p.executeUpdate();
    }
  }

  private boolean withinAvailability(JsonObject availability, LocalDateTime start, LocalDateTime end) {
    String key = dayKey(start.getDayOfWeek());
    if (!availability.has(key) || !availability.get(key).isJsonArray()) return false;
    for (JsonElement element : availability.getAsJsonArray(key)) {
      JsonObject slot = element.getAsJsonObject(); LocalTime from = LocalTime.parse(required(slot, "start")); LocalTime to = LocalTime.parse(required(slot, "end"));
      if (!start.toLocalTime().isBefore(from) && !end.toLocalTime().isAfter(to) && start.toLocalDate().equals(end.toLocalDate())) return true;
    }
    return false;
  }

  private void validateAvailability(JsonObject availability) {
    boolean hasSlot = false;
    for (String day : availability.keySet()) {
      if (!availability.get(day).isJsonArray()) throw new IllegalArgumentException("availability_invalid");
      for (JsonElement element : availability.getAsJsonArray(day)) {
        if (!element.isJsonObject()) throw new IllegalArgumentException("availability_invalid");
        JsonObject slot = element.getAsJsonObject(); LocalTime start = LocalTime.parse(required(slot, "start")); LocalTime end = LocalTime.parse(required(slot, "end"));
        if (!start.isBefore(end)) throw new IllegalArgumentException("availability_invalid");
        hasSlot = true;
      }
    }
    if (!hasSlot) throw new IllegalArgumentException("availability_required");
  }

  private String dayKey(DayOfWeek day) { return day.name().toLowerCase(Locale.ROOT); }
  private boolean hasSchedulingFields(JsonObject value) {
    return value.has("recurrenceType") || value.has("scheduleStartDate")
        || value.has("recurrenceEndDate") || value.has("scheduledTime");
  }
  private LocalDate localDate(JsonObject value, String name) {
    String text = nullable(value, name); return text == null || text.isBlank() ? null : LocalDate.parse(text);
  }
  private LocalTime localTime(JsonObject value, String name) {
    String text = nullable(value, name); return text == null || text.isBlank() ? null : LocalTime.parse(text);
  }
  private java.sql.Date sqlDate(LocalDate value) { return value == null ? null : java.sql.Date.valueOf(value); }
  private java.sql.Date date(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : java.sql.Date.valueOf(text); }
  private UUID uuid(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : UUID.fromString(text); }
  private byte[] bytes(UUID value) { return value == null ? null : Database.uuidBytes(value); }
  private void addUuid(JsonObject row, String name, byte[] value) { row.addProperty(name, value == null ? null : Database.bytesUuid(value).toString()); }
  private String required(JsonObject value, String name) { String result = string(value, name, "").trim(); if (result.isBlank()) throw new IllegalArgumentException(name + "_required"); return result; }
  private String string(JsonObject value, String name, String fallback) { JsonElement item = value.get(name); return item == null || item.isJsonNull() ? fallback : item.getAsString(); }
  private String nullable(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null; }
  private Integer optionalInt(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsInt() : null; }
  private int intValue(JsonObject value, String name, int fallback) { Integer result = optionalInt(value, name); return result == null ? fallback : result; }
  private String priority(JsonObject value) { String result = string(value, "priority", "medium"); if (!List.of("high", "medium", "low").contains(result)) throw new IllegalArgumentException("invalid_priority"); return result; }
  private Timestamp timestamp(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : Timestamp.valueOf(LocalDateTime.parse(text)); }
  private void setInteger(PreparedStatement p, int index, Integer value) throws SQLException { if (value == null) p.setObject(index, null); else p.setInt(index, value); }
}
