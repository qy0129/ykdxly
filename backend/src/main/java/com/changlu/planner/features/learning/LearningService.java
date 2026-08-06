package com.changlu.planner.features.learning;

import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习规划应用服务层。
 * Subagent 不直接操作数据库——所有学习数据的读写通过本服务完成，
 * 自动携带用户、工作区上下文，并提供幂等和参数校验。
 */
public final class LearningService {
  private static final Logger LOG = LoggerFactory.getLogger(LearningService.class);
  private final Database database;

  public LearningService(Database database) {
    this.database = database;
  }

  // ==================== 学习目标 CRUD ====================

  /** 列出用户的有效学习目标。 */
  public List<LearningGoal> listGoals(Database.Context context) throws SQLException {
    String sql = """
        SELECT id, plan_id, title, description, domain, priority, target_date,
               weekly_hours, status, target_metrics, milestones, progress, total_sessions, completed_sessions,
               total_minutes, version, created_at, updated_at
        FROM learning_goals
        WHERE workspace_id=? AND user_id=? AND deleted_at IS NULL
        ORDER BY priority='high' DESC, priority, target_date IS NULL, target_date, created_at DESC
        LIMIT 50""";
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        List<LearningGoal> goals = new ArrayList<>();
        while (rs.next()) {
          goals.add(new LearningGoal(
              Database.id(rs, "id"),
              planId(rs),
              rs.getString("title"),
              rs.getString("description"),
              rs.getString("domain"),
              rs.getString("priority"),
              rs.getDate("target_date") != null ? rs.getDate("target_date").toLocalDate() : null,
              rs.getObject("weekly_hours") != null ? rs.getDouble("weekly_hours") : null,
              rs.getString("status"),
              jsonArray(rs, "target_metrics"),
              jsonArray(rs, "milestones"),
              rs.getDouble("progress"),
              rs.getInt("total_sessions"),
              rs.getInt("completed_sessions"),
              rs.getInt("total_minutes"),
              rs.getInt("version"),
              rs.getTimestamp("created_at").toLocalDateTime(),
              rs.getTimestamp("updated_at").toLocalDateTime()
          ));
        }
        return goals;
      }
    }
  }

  /** 获取单个学习目标。 */
  public LearningGoal getGoal(String goalId, Database.Context context) throws SQLException {
    try (Connection c = database.connection()) { return getGoal(c, goalId, context); }
  }

  /** 事务内读取学习目标（确认草案时与计划级联删除共用同一连接，保证原子性）。 */
  public LearningGoal getGoal(Connection c, String goalId, Database.Context context) throws SQLException {
    String sql = """
        SELECT id, plan_id, title, description, domain, priority, target_date,
               weekly_hours, status, target_metrics, milestones, progress, total_sessions, completed_sessions,
               total_minutes, version, created_at, updated_at
        FROM learning_goals
        WHERE id=? AND workspace_id=? AND deleted_at IS NULL""";
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(goalId)));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        return new LearningGoal(
            Database.id(rs, "id"),
            planId(rs),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("domain"),
            rs.getString("priority"),
            rs.getDate("target_date") != null ? rs.getDate("target_date").toLocalDate() : null,
            rs.getObject("weekly_hours") != null ? rs.getDouble("weekly_hours") : null,
            rs.getString("status"),
            jsonArray(rs, "target_metrics"),
            jsonArray(rs, "milestones"),
            rs.getDouble("progress"),
            rs.getInt("total_sessions"),
            rs.getInt("completed_sessions"),
            rs.getInt("total_minutes"),
            rs.getInt("version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime()
        );
      }
    }
  }

  // ==================== 学习会话 ====================

  /** 列出近期的学习会话。 */
  /** Create a learning goal. */
  public JsonObject createGoal(Database.Context context, JsonObject input) throws SQLException {
    UUID id = UUID.randomUUID();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO learning_goals (id,workspace_id,user_id,plan_id,title,description,domain,priority,target_date,weekly_hours,status,target_metrics,milestones) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      p.setBytes(4, input.has("planId") && !input.get("planId").isJsonNull() ? Database.uuidBytes(UUID.fromString(input.get("planId").getAsString())) : null);
      p.setString(5, required(input, "title")); p.setString(6, nullable(input, "description"));
      p.setString(7, value(input, "domain", "general")); p.setString(8, value(input, "priority", "medium"));
      p.setObject(9, date(input, "targetDate"));
      p.setObject(10, input.has("weeklyHours") && !input.get("weeklyHours").isJsonNull() ? input.get("weeklyHours").getAsDouble() : null);
      p.setString(11, value(input, "status", "active"));
      p.setString(12, jsonText(input, "targetMetrics"));
      p.setString(13, jsonText(input, "milestones"));
      p.executeUpdate();
    }
    LearningGoal goal = getGoal(id.toString(), context);
    return goal == null ? new JsonObject() : goal.toJson();
  }

  /** Update a learning goal with optimistic version checking. */
  public JsonObject updateGoal(String goalId, Database.Context context, JsonObject input) throws SQLException {
    LearningGoal before = getGoal(goalId, context);
    if (before == null) throw new IllegalArgumentException("learning_goal_not_found");
    int expected = input.has("expectedVersion") ? input.get("expectedVersion").getAsInt() : before.version();
    String sql = "UPDATE learning_goals SET title=COALESCE(?,title),description=COALESCE(?,description),domain=COALESCE(?,domain),priority=COALESCE(?,priority),target_date=COALESCE(?,target_date),weekly_hours=COALESCE(?,weekly_hours),status=COALESCE(?,status),target_metrics=COALESCE(?,target_metrics),milestones=COALESCE(?,milestones),version=version+1 WHERE id=? AND workspace_id=? AND user_id=? AND version=? AND deleted_at IS NULL";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, nullable(input, "title")); p.setString(2, nullable(input, "description"));
      p.setString(3, nullable(input, "domain")); p.setString(4, nullable(input, "priority")); p.setObject(5, date(input, "targetDate"));
      p.setObject(6, input.has("weeklyHours") && !input.get("weeklyHours").isJsonNull() ? input.get("weeklyHours").getAsDouble() : null);
      p.setString(7, nullable(input, "status"));
      p.setString(8, jsonText(input, "targetMetrics"));
      p.setString(9, jsonText(input, "milestones"));
      p.setBytes(10, Database.uuidBytes(UUID.fromString(goalId)));
      p.setBytes(11, Database.uuidBytes(context.workspaceId())); p.setBytes(12, Database.uuidBytes(context.userId())); p.setInt(13, expected);
      if (p.executeUpdate() == 0) throw new IllegalStateException("learning_goal_version_conflict");
    }
    LearningGoal goal = getGoal(goalId, context);
    return goal == null ? new JsonObject() : goal.toJson();
  }

  /** Soft delete a learning goal. */
  public void deleteGoal(String goalId, Database.Context context, int expectedVersion) throws SQLException {
    try (Connection c = database.connection()) { deleteGoal(c, goalId, context, expectedVersion); }
  }

  /** 事务内软删学习目标。 */
  public void deleteGoal(Connection c, String goalId, Database.Context context, int expectedVersion) throws SQLException {
    try (PreparedStatement p = c.prepareStatement(
        "UPDATE learning_goals SET deleted_at=NOW(),version=version+1 WHERE id=? AND workspace_id=? AND user_id=? AND version=? AND deleted_at IS NULL")) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(goalId))); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId())); p.setInt(4, expectedVersion);
      if (p.executeUpdate() == 0) throw new IllegalStateException("learning_goal_version_conflict");
    }
  }

  private static String value(JsonObject input, String name, String fallback) {
    return input.has(name) && !input.get(name).isJsonNull() ? input.get(name).getAsString() : fallback;
  }
  private static String nullable(JsonObject input, String name) {
    return input.has(name) && !input.get(name).isJsonNull() ? input.get(name).getAsString() : null;
  }
  private static String required(JsonObject input, String name) {
    String value = nullable(input, name);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "_required");
    return value.trim();
  }
  private static java.sql.Date date(JsonObject input, String name) {
    String value = nullable(input, name);
    return value == null || value.isBlank() ? null : java.sql.Date.valueOf(LocalDate.parse(value));
  }
  private static String planId(ResultSet rs) throws SQLException {
    byte[] value = rs.getBytes("plan_id");
    return value == null ? null : Database.bytesUuid(value).toString();
  }
  private static JsonArray jsonArray(ResultSet rs, String name) throws SQLException {
    String value = rs.getString(name);
    if (value == null || value.isBlank()) return new JsonArray();
    try {
      JsonElement parsed = JsonParser.parseString(value);
      return parsed.isJsonArray() ? parsed.getAsJsonArray() : new JsonArray();
    } catch (RuntimeException ignored) { return new JsonArray(); }
  }
  private static String jsonText(JsonObject input, String name) {
    return input.has(name) && input.get(name).isJsonArray() ? input.get(name).getAsJsonArray().toString() : null;
  }

  public List<LearningSession> listSessions(Database.Context context, int days) throws SQLException {
    String sql = """
        SELECT ls.id, ls.title, ls.domain, ls.planned_minutes, ls.actual_minutes,
               ls.status, ls.focus_score, ls.notes, ls.completed_at, ls.created_at,
               lg.title AS goal_title
        FROM learning_sessions ls
        LEFT JOIN learning_goals lg ON ls.goal_id = lg.id AND lg.deleted_at IS NULL
        WHERE ls.workspace_id=? AND ls.user_id=?
          AND ls.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
        ORDER BY ls.created_at DESC LIMIT 100""";
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      p.setInt(3, days);
      try (ResultSet rs = p.executeQuery()) {
        List<LearningSession> sessions = new ArrayList<>();
        while (rs.next()) {
          sessions.add(new LearningSession(
              Database.id(rs, "id"),
              rs.getString("title"),
              rs.getString("domain"),
              rs.getInt("planned_minutes"),
              rs.getObject("actual_minutes") != null ? rs.getInt("actual_minutes") : null,
              rs.getString("status"),
              rs.getObject("focus_score") != null ? rs.getInt("focus_score") : null,
              rs.getString("notes"),
              rs.getTimestamp("completed_at") != null
                  ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
              rs.getTimestamp("created_at").toLocalDateTime(),
              rs.getString("goal_title")
          ));
        }
        return sessions;
      }
    }
  }

  /** 列出单个学习目标的学习会话。 */
  public List<LearningSession> listSessionsByGoal(String goalId, Database.Context context) throws SQLException {
    String sql = """
        SELECT ls.id, ls.title, ls.domain, ls.planned_minutes, ls.actual_minutes,
               ls.status, ls.focus_score, ls.notes, ls.completed_at, ls.created_at,
               lg.title AS goal_title
        FROM learning_sessions ls
        LEFT JOIN learning_goals lg ON ls.goal_id = lg.id AND lg.deleted_at IS NULL
        WHERE ls.workspace_id=? AND ls.user_id=? AND ls.goal_id=?
        ORDER BY ls.created_at DESC LIMIT 100""";
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      p.setBytes(3, Database.uuidBytes(UUID.fromString(goalId)));
      try (ResultSet rs = p.executeQuery()) {
        List<LearningSession> sessions = new ArrayList<>();
        while (rs.next()) {
          sessions.add(new LearningSession(
              Database.id(rs, "id"),
              rs.getString("title"),
              rs.getString("domain"),
              rs.getInt("planned_minutes"),
              rs.getObject("actual_minutes") != null ? rs.getInt("actual_minutes") : null,
              rs.getString("status"),
              rs.getObject("focus_score") != null ? rs.getInt("focus_score") : null,
              rs.getString("notes"),
              rs.getTimestamp("completed_at") != null
                  ? rs.getTimestamp("completed_at").toLocalDateTime() : null,
              rs.getTimestamp("created_at").toLocalDateTime(),
              rs.getString("goal_title")
          ));
        }
        return sessions;
      }
    }
  }

  /** 学习目标关联计划下的任务（含阶段名），供详情页按天展示。 */
  public List<PlanTask> planTasks(String planId, Database.Context context) throws SQLException {
    String sql = """
        SELECT t.id, t.title, t.description, t.due_at, t.estimated_minutes, t.status, t.version, s.title AS stage_title
        FROM plan_tasks t
        LEFT JOIN plan_stages s ON s.id = t.stage_id AND s.deleted_at IS NULL
        JOIN plans p ON p.id = t.plan_id
        WHERE t.plan_id=? AND p.workspace_id=? AND t.deleted_at IS NULL
        ORDER BY t.due_at, t.sort_order, t.created_at""";
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(planId)));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        List<PlanTask> tasks = new ArrayList<>();
        while (rs.next()) {
          tasks.add(new PlanTask(
              Database.id(rs, "id"),
              rs.getString("title"),
              rs.getString("description"),
              rs.getTimestamp("due_at") != null
                  ? rs.getTimestamp("due_at").toLocalDateTime() : null,
              rs.getObject("estimated_minutes") != null ? rs.getInt("estimated_minutes") : null,
              rs.getString("status"),
              rs.getInt("version"),
              rs.getString("stage_title")
          ));
        }
        return tasks;
      }
    }
  }

  public record PlanTask(String id, String title, String description, LocalDateTime dueAt,
                         Integer minutes, String status, int version, String stageTitle) {
    public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty("id", id);
      obj.addProperty("title", title);
      obj.addProperty("description", description != null ? description : "");
      obj.addProperty("dueAt", dueAt != null ? dueAt.toString() : null);
      obj.addProperty("minutes", minutes);
      obj.addProperty("status", status);
      obj.addProperty("version", version);
      obj.addProperty("stageTitle", stageTitle != null ? stageTitle : "");
      return obj;
    }
  }

  // ==================== 知识领域 ====================

  /** 列出所有知识领域。 */
  public List<KnowledgeArea> listKnowledgeAreas(Database.Context context) throws SQLException {
    String sql = """
        SELECT id, name, parent_id, description, mastery_level, last_studied_at
        FROM knowledge_areas
        WHERE workspace_id=?
        ORDER BY parent_id IS NULL DESC, parent_id, name""";
    try (Connection c = database.connection();
         PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      try (ResultSet rs = p.executeQuery()) {
        List<KnowledgeArea> areas = new ArrayList<>();
        while (rs.next()) {
          areas.add(new KnowledgeArea(
              Database.id(rs, "id"),
              rs.getString("name"),
              rs.getString("parent_id") != null ? Database.id(rs, "parent_id") : null,
              rs.getString("description"),
              rs.getInt("mastery_level"),
              rs.getTimestamp("last_studied_at") != null
                  ? rs.getTimestamp("last_studied_at").toLocalDateTime() : null
          ));
        }
        return areas;
      }
    }
  }

  // ==================== 学习统计 ====================

  /** 获取学习统计摘要。 */
  public LearningStats stats(Database.Context context) throws SQLException {
    try (Connection c = database.connection()) {
      int activeGoals = 0;
      int totalSessions = 0;
      int totalMinutes = 0;
      double avgFocus = 0;
      int currentStreak = 0;
      int weeklyMinutes = 0;

      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*) FROM learning_goals WHERE workspace_id=? AND user_id=? AND status='active' AND deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) activeGoals = rs.getInt(1); }
      }

      try (PreparedStatement p = c.prepareStatement(
          "SELECT COUNT(*), COALESCE(SUM(actual_minutes),0), COALESCE(AVG(focus_score),0) FROM learning_sessions WHERE workspace_id=? AND user_id=? AND status='completed' AND completed_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) {
          if (rs.next()) {
            totalSessions = rs.getInt(1);
            totalMinutes = rs.getInt(2);
            avgFocus = rs.getDouble(3);
          }
        }
      }

      try (PreparedStatement p = c.prepareStatement(
          "SELECT COALESCE(SUM(actual_minutes),0) FROM learning_sessions WHERE workspace_id=? AND user_id=? AND status='completed' AND completed_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        p.setBytes(2, Database.uuidBytes(context.userId()));
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) weeklyMinutes = rs.getInt(1); }
      }

      // 计算连续学习天数
      currentStreak = calculateStreak(c, context);

      return new LearningStats(activeGoals, totalSessions, totalMinutes,
          Math.round(avgFocus * 10.0) / 10.0, currentStreak, weeklyMinutes);
    }
  }

  private int calculateStreak(Connection c, Database.Context context) throws SQLException {
    int streak = 0;
    LocalDate check = LocalDate.now();
    try (PreparedStatement p = c.prepareStatement(
        "SELECT COUNT(*) FROM learning_sessions WHERE workspace_id=? AND user_id=? AND status='completed' AND DATE(completed_at)=?")) {
      while (streak < 365) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId()));
        p.setBytes(2, Database.uuidBytes(context.userId()));
        p.setDate(3, java.sql.Date.valueOf(check));
        try (ResultSet rs = p.executeQuery()) {
          if (rs.next() && rs.getInt(1) > 0) { streak++; check = check.minusDays(1); }
          else break;
        }
      }
    }
    return streak;
  }

  // ==================== 数据模型 ====================

  public record LearningGoal(
      String id, String planId, String title, String description, String domain,
      String priority, LocalDate targetDate, Double weeklyHours,
      String status, JsonArray targetMetrics, JsonArray milestones,
      double progress, int totalSessions,
      int completedSessions, int totalMinutes, int version,
      LocalDateTime createdAt, LocalDateTime updatedAt
  ) {
    public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty("id", id);
      obj.addProperty("planId", planId != null ? planId : "");
      obj.addProperty("title", title);
      obj.addProperty("description", description != null ? description : "");
      obj.addProperty("domain", domain);
      obj.addProperty("priority", priority);
      obj.addProperty("targetDate", targetDate != null ? targetDate.toString() : null);
      obj.addProperty("weeklyHours", weeklyHours);
      obj.addProperty("status", status);
      obj.add("targetMetrics", targetMetrics != null ? targetMetrics.deepCopy() : new JsonArray());
      obj.add("milestones", milestones != null ? milestones.deepCopy() : new JsonArray());
      obj.addProperty("progress", progress);
      obj.addProperty("totalSessions", totalSessions);
      obj.addProperty("completedSessions", completedSessions);
      obj.addProperty("totalMinutes", totalMinutes);
      obj.addProperty("version", version);
      obj.addProperty("createdAt", createdAt.toString());
      obj.addProperty("updatedAt", updatedAt.toString());
      return obj;
    }
  }

  public record LearningSession(
      String id, String title, String domain, int plannedMinutes,
      Integer actualMinutes, String status, Integer focusScore,
      String notes, LocalDateTime completedAt, LocalDateTime createdAt,
      String goalTitle
  ) {
    public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty("id", id);
      obj.addProperty("title", title);
      obj.addProperty("domain", domain);
      obj.addProperty("plannedMinutes", plannedMinutes);
      obj.addProperty("actualMinutes", actualMinutes);
      obj.addProperty("status", status);
      obj.addProperty("focusScore", focusScore);
      obj.addProperty("notes", notes != null ? notes : "");
      obj.addProperty("completedAt", completedAt != null ? completedAt.toString() : null);
      obj.addProperty("createdAt", createdAt.toString());
      obj.addProperty("goalTitle", goalTitle != null ? goalTitle : "");
      return obj;
    }
  }

  public record KnowledgeArea(
      String id, String name, String parentId, String description,
      int masteryLevel, LocalDateTime lastStudiedAt
  ) {
    public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty("id", id);
      obj.addProperty("name", name);
      obj.addProperty("parentId", parentId != null ? parentId : "");
      obj.addProperty("description", description != null ? description : "");
      obj.addProperty("masteryLevel", masteryLevel);
      obj.addProperty("lastStudiedAt", lastStudiedAt != null ? lastStudiedAt.toString() : null);
      return obj;
    }
  }

  public record LearningStats(
      int activeGoals, int totalSessions, int totalMinutes,
      double avgFocusScore, int currentStreak, int weeklyMinutes
  ) {
    public JsonObject toJson() {
      JsonObject obj = new JsonObject();
      obj.addProperty("activeGoals", activeGoals);
      obj.addProperty("totalSessions30d", totalSessions);
      obj.addProperty("totalMinutes30d", totalMinutes);
      obj.addProperty("avgFocusScore", avgFocusScore);
      obj.addProperty("currentStreak", currentStreak);
      obj.addProperty("weeklyMinutes", weeklyMinutes);
      return obj;
    }
  }
}
