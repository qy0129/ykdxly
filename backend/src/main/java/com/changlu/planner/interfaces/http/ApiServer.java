package com.changlu.planner.interfaces.http;

import com.changlu.planner.features.briefing.BriefingSubAgent;
import com.changlu.planner.features.export.StatsPdfGenerator;
import com.changlu.planner.features.review.AiReviewService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** HTTP 适配层：负责把外部请求转换为现有业务方法，不保存独立的业务状态。 */
public final class ApiServer {
  private final Database database;
  private final int port;
  private final Gson gson = new Gson();
  private final AiReviewService aiReview;
  private final BriefingSubAgent briefingAgent;
  private final StaticFileHandler staticFiles = new StaticFileHandler();
  private HttpServer server;

  public ApiServer(Database database, int port) { this.database = database; this.port = port; this.aiReview = new AiReviewService(database); this.briefingAgent = new BriefingSubAgent(database); }
  public int port() { return port; }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    // 路由按外部能力分组；静态文件由独立 handler 托管，避免和 API 逻辑混在一起。
    server.createContext("/api/health", this::health);
    server.createContext("/api/profile", this::profile);
    server.createContext("/api/plans", exchange -> crud(exchange, "plans"));
    server.createContext("/api/plans/", this::planSubresource);
    server.createContext("/api/schedules", exchange -> crud(exchange, "schedule_items"));
    server.createContext("/api/todos", exchange -> crud(exchange, "todos"));
    server.createContext("/api/notes", this::notes);
    server.createContext("/api/ai/review/chat", this::aiReviewChat);
    server.createContext("/api/stats", this::stats);
    server.createContext("/api/export/xlsx", this::excelExport);
    server.createContext("/api/export/pdf", this::pdfExport);
    server.createContext("/api/integrations/wechat/capture", this::wechatCapture);
    server.createContext("/api/integrations/wechat/command", this::wechatCommand);
    server.createContext("/api/integrations/wechat/briefing", this::wechatBriefing);
    server.createContext("/", staticFiles);
    server.setExecutor(null);
    server.start();
  }
  public void stop() { if (server != null) server.stop(0); }

  private void health(HttpExchange e) throws IOException { json(e, 200, Map.of("ok", true, "service", "changlu-planner")); }

  private void profile(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if ("GET".equals(e.getRequestMethod())) {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "SELECT display_name, avatar_url FROM users WHERE id = ?")) {
          p.setBytes(1, Database.uuidBytes(user(e)));
          try (ResultSet rs = p.executeQuery()) {
            if (!rs.next()) { json(e, 404, Map.of("error", "user_not_found")); return; }
            json(e, 200, Map.of("displayName", rs.getString("display_name"), "avatarUrl", rs.getString("avatar_url")));
          }
        }
        return;
      }
      if ("PUT".equals(e.getRequestMethod()) || "POST".equals(e.getRequestMethod())) {
        JsonObject body = body(e);
        String displayName = string(body, "displayName", "").trim();
        if (displayName.isBlank()) { json(e, 400, Map.of("error", "display_name_required")); return; }
        String avatarUrl = string(body, "avatarUrl", "").trim();
        if (avatarUrl.length() > 1000) { json(e, 400, Map.of("error", "avatar_url_too_long")); return; }
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "UPDATE users SET display_name = ?, avatar_url = ? WHERE id = ?")) {
          p.setString(1, displayName); p.setString(2, avatarUrl); p.setBytes(3, Database.uuidBytes(user(e)));
          if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "user_not_found")); return; }
        }
        json(e, 200, Map.of("displayName", displayName, "avatarUrl", avatarUrl));
        return;
      }
      json(e, 405, Map.of("error", "method_not_allowed"));
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void aiReviewChat(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, aiReview.chat(body(e), workspace(e), user(e)));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (IllegalStateException ex) { json(e, 503, Map.of("error", "ai_unavailable", "message", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "ai_error", "message", ex.getMessage())); }
  }

  private void wechatCapture(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      JsonObject body = body(e);
      String text = string(body, "text", "").trim();
      String externalId = e.getRequestHeaders().getFirst("X-Wechat-User-Id");
      if (text.isBlank()) { json(e, 400, Map.of("error", "text_required")); return; }
      String type = null, title = text;
      String[] prefixes = {"计划:", "计划：", "待办:", "待办：", "日程:", "日程：", "笔记:", "笔记："};
      for (String prefix : prefixes) if (text.startsWith(prefix)) { type = prefix.substring(0, 2); title = text.substring(prefix.length()).trim(); break; }
      if (type == null) { json(e, 200, Map.of("handled", false)); return; }
      if (title.isBlank()) { json(e, 400, Map.of("error", "title_required")); return; }
      Database.Context context = database.contextForExternalUser(externalId);
      UUID id = UUID.randomUUID();
      if (type.equals("计划")) insertPlan(context, id, title);
      else if (type.equals("待办")) insertTodo(context, id, title);
      else if (type.equals("日程")) insertSchedule(context, id, title);
      else insertNote(context, id, title);
      json(e, 201, Map.of("handled", true, "type", type, "id", id.toString(), "title", title, "message", "已记录到长路计划"));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void wechatCommand(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      String text = string(body(e), "text", "").trim();
      if (text.isBlank()) { json(e, 400, Map.of("error", "text_required")); return; }
      Database.Context context = database.contextForExternalUser(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      String normalized = text.replaceAll("[\\s，。！？、,.!?]", "");
      JsonObject result = new JsonObject(); result.addProperty("handled", true);
      if (normalized.equals("今天还有什么") || normalized.equals("今天还有哪些")) {
        result.addProperty("message", todayOpenItems(context));
      } else if (normalized.equals("计划完成得怎么样")) {
        result.addProperty("message", progressSummary(context));
      } else if (normalized.startsWith("完成:" ) || normalized.startsWith("完成：")) {
        result.addProperty("message", completeByTitle(context, text.substring(3).trim()));
      } else if (normalized.startsWith("删除:" ) || normalized.startsWith("删除：")) {
        String title = text.substring(3).trim();
        result.addProperty("message", "删除会移除这条记录。请回复“确认删除：" + title + "”确认。");
      } else if (normalized.startsWith("确认删除:" ) || normalized.startsWith("确认删除：")) {
        result.addProperty("message", deleteByTitle(context, text.substring(5).trim()));
      } else {
        result.addProperty("handled", false);
      }
      json(e, 200, result);
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private String todayOpenItems(Database.Context context) throws SQLException {
    List<String> schedules = new ArrayList<>();
    List<String> todos = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT title FROM schedule_items WHERE workspace_id = ? AND DATE(start_at) = CURDATE() AND status <> 'done' ORDER BY start_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add("日程：" + rs.getString(1)); }
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT title FROM todos WHERE workspace_id = ? AND (due_at IS NULL OR DATE(due_at) = CURDATE()) AND status <> 'done' ORDER BY due_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add("待办：" + rs.getString(1)); }
    }
    List<String> all = new ArrayList<>(); all.addAll(schedules); all.addAll(todos);
    if (all.isEmpty()) return "今天没有未完成的日程或待办，按自己的节奏休息一下。";
    return "今天还有：\n- " + String.join("\n- ", all);
  }

  private String progressSummary(Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*), COALESCE(AVG(progress), 0) FROM plans WHERE workspace_id = ? AND status = 'active'")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return "暂时还没有长期计划。";
        return "当前有 " + rs.getInt(1) + " 个进行中的长期计划，平均完成度 " + Math.round(rs.getDouble(2)) + "%。";
      }
    }
  }

  private String completeByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title);
    if (item == null) return "没有找到名称包含“" + title + "”的计划、日程或待办。";
    String status = item.table.equals("plans") ? "completed" : "done";
    String sql = item.table.equals("todos")
        ? "UPDATE todos SET status = ? WHERE id = ? AND workspace_id = ?"
        : "UPDATE " + item.table + " SET status = ?, progress = 100 WHERE id = ? AND workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, status); p.setBytes(2, Database.uuidBytes(item.id)); p.setBytes(3, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
    }
    return "已完成：" + item.title;
  }

  private String deleteByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title);
    if (item == null) return "没有找到名称包含“" + title + "”的记录。";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM " + item.table + " WHERE id = ? AND workspace_id = ?")) {
      p.setBytes(1, Database.uuidBytes(item.id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
    }
    return "已删除：" + item.title;
  }

  private ItemRef findByTitle(Database.Context context, String title) throws SQLException {
    String[] tables = {"todos", "schedule_items", "plans"};
    for (String table : tables) {
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT id, title FROM " + table + " WHERE workspace_id = ? AND title LIKE ? ORDER BY updated_at DESC LIMIT 1")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setString(2, "%" + title + "%");
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) return new ItemRef(table, Database.bytesUuid(rs.getBytes("id")), rs.getString("title")); }
      }
    }
    return null;
  }

  private record ItemRef(String table, UUID id, String title) {}

  private void insertPlan(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO plans (id, workspace_id, owner_id, title, description, color) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setString(5, "微信快速记录"); p.setString(6, "#D39A24"); p.executeUpdate();
    }
  }

  private void insertTodo(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO todos (id, workspace_id, created_by, title, due_at) VALUES (?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now())); p.executeUpdate();
    }
  }

  private void insertSchedule(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO schedule_items (id, workspace_id, created_by, title, start_at) VALUES (?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now())); p.executeUpdate();
    }
  }

  private void insertNote(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO notes (id, workspace_id, created_by, title, excerpt, content) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setString(5, "微信快速记录"); p.setString(6, title); p.executeUpdate();
    }
  }

  private void wechatBriefing(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      BriefingSubAgent.Result briefing = briefingAgent.build(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      json(e, 200, Map.of("plans", briefing.plans(), "progress", briefing.progress(), "pendingTodos", briefing.pendingTodos(), "overdueTodos", briefing.overdueTodos(), "tone", briefing.tone(), "message", briefing.message()));
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void planSubresource(HttpExchange e) throws IOException {
    String[] parts = e.getRequestURI().getPath().split("/");
    if (parts.length < 5 || !"stages".equals(parts[4])) { crud(e, "plans"); return; }
    try {
      if (options(e)) return;
      String planId = parts[3]; requireId(planId);
      String stageId = parts.length > 5 && !parts[5].isBlank() ? parts[5] : null;
      switch (e.getRequestMethod()) {
        case "GET" -> listPlanStages(e, planId);
        case "POST" -> createPlanStage(e, planId);
        case "PUT", "PATCH" -> { requireId(stageId); updatePlanStage(e, planId, stageId); }
        case "DELETE" -> { requireId(stageId); deletePlanStage(e, planId, stageId); }
        default -> json(e, 405, Map.of("error", "method_not_allowed"));
      }
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void listPlanStages(HttpExchange e, String planId) throws SQLException, IOException {
    String sql = "SELECT s.* FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.plan_id = ? AND p.workspace_id = ? ORDER BY s.sort_order, s.created_at";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(planId))); p.setBytes(2, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) rows.add(stageRow(rs)); json(e, 200, rows); }
    }
  }

  private void createPlanStage(HttpExchange e, String planId) throws SQLException, IOException {
    JsonObject b = body(e); UUID id = UUID.randomUUID(); UUID plan = UUID.fromString(planId);
    String sql = "INSERT INTO plan_stages (id, plan_id, title, progress, status, due_date, sort_order) SELECT ?, id, ?, ?, ?, ?, ? FROM plans WHERE id = ? AND workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      double progress = number(b, "progress", 0);
      p.setBytes(1, Database.uuidBytes(id)); p.setString(2, string(b, "title", "未命名阶段")); p.setDouble(3, progress);
      p.setString(4, progress >= 100 ? "done" : "pending"); p.setObject(5, safeDate(string(b, "dueLabel", null)));
      p.setInt(6, (int) number(b, "sortOrder", 0)); p.setBytes(7, Database.uuidBytes(plan)); p.setBytes(8, Database.uuidBytes(workspace(e)));
      if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "plan_not_found")); return; }
      updatePlanProgress(c, plan);
    }
    getPlanStage(e, planId, id.toString());
  }

  private void updatePlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    JsonObject b = body(e); UUID plan = UUID.fromString(planId); UUID stage = UUID.fromString(stageId);
    String sql = "UPDATE plan_stages s JOIN plans p ON p.id = s.plan_id SET s.title = ?, s.progress = ?, s.status = ?, s.due_date = ? WHERE s.id = ? AND s.plan_id = ? AND p.workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      double progress = number(b, "progress", 0);
      p.setString(1, string(b, "title", "未命名阶段")); p.setDouble(2, progress); p.setString(3, progress >= 100 ? "done" : "pending");
      p.setObject(4, safeDate(string(b, "dueLabel", null))); p.setBytes(5, Database.uuidBytes(stage)); p.setBytes(6, Database.uuidBytes(plan)); p.setBytes(7, Database.uuidBytes(workspace(e)));
      if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "stage_not_found")); return; }
      updatePlanProgress(c, plan);
    }
    getPlanStage(e, planId, stageId);
  }

  private void deletePlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    UUID plan = UUID.fromString(planId);
    String sql = "DELETE s FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.id = ? AND s.plan_id = ? AND p.workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(stageId))); p.setBytes(2, Database.uuidBytes(plan)); p.setBytes(3, Database.uuidBytes(workspace(e)));
      if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "stage_not_found")); return; }
      updatePlanProgress(c, plan);
    }
    json(e, 204, Map.of("deleted", true));
  }

  private void getPlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    String sql = "SELECT s.* FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.id = ? AND s.plan_id = ? AND p.workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(stageId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(planId))); p.setBytes(3, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) { json(e, 404, Map.of("error", "stage_not_found")); return; } json(e, 200, stageRow(rs)); }
    }
  }

  private JsonObject stageRow(ResultSet rs) throws SQLException {
    JsonObject o = new JsonObject(); o.addProperty("id", Database.id(rs, "id")); o.addProperty("title", rs.getString("title"));
    o.addProperty("progress", rs.getDouble("progress")); o.addProperty("status", rs.getString("status"));
    o.addProperty("dueLabel", rs.getDate("due_date") == null ? "待安排" : rs.getDate("due_date").toString());
    return o;
  }

  private void updatePlanProgress(Connection c, UUID planId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("UPDATE plans SET progress = (SELECT COALESCE(AVG(progress), 0) FROM plan_stages WHERE plan_id = ?) WHERE id = ?")) {
      p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(planId)); p.executeUpdate();
    }
  }

  private java.sql.Date safeDate(String value) {
    if (value == null || value.isBlank()) return null;
    try { return java.sql.Date.valueOf(value); } catch (IllegalArgumentException ignored) { return null; }
  }

  private void stats(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, loadStats(workspace(e)));
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private JsonObject loadStats(UUID workspace) throws SQLException {
      LocalDate today = LocalDate.now(); LocalDate from = today.minusDays(118);
      Map<LocalDate, DailyStat> values = new LinkedHashMap<>();
      for (LocalDate date = from; !date.isAfter(today); date = date.plusDays(1)) values.put(date, new DailyStat());
      String scheduleSql = "SELECT DATE(start_at) day, COUNT(*) planned, SUM(status = 'done') completed, SUM(CASE WHEN status = 'done' THEN duration_minutes ELSE 0 END) minutes FROM schedule_items WHERE workspace_id = ? AND DATE(start_at) BETWEEN ? AND ? GROUP BY DATE(start_at)";
      String todoSql = "SELECT DATE(COALESCE(due_at, created_at)) day, COUNT(*) planned, SUM(status = 'done') completed FROM todos WHERE workspace_id = ? AND DATE(COALESCE(due_at, created_at)) BETWEEN ? AND ? GROUP BY DATE(COALESCE(due_at, created_at))";
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement(scheduleSql)) { p.setBytes(1, Database.uuidBytes(workspace)); p.setDate(2, java.sql.Date.valueOf(from)); p.setDate(3, java.sql.Date.valueOf(today)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) values.get(rs.getDate("day").toLocalDate()).add(rs.getInt("planned"), rs.getInt("completed"), rs.getInt("minutes")); } }
        try (PreparedStatement p = c.prepareStatement(todoSql)) { p.setBytes(1, Database.uuidBytes(workspace)); p.setDate(2, java.sql.Date.valueOf(from)); p.setDate(3, java.sql.Date.valueOf(today)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) values.get(rs.getDate("day").toLocalDate()).add(rs.getInt("planned"), rs.getInt("completed"), 0); } }
      }
      return statsPayload(values, today);
  }

  private JsonObject statsPayload(Map<LocalDate, DailyStat> values, LocalDate today) {
    JsonObject result = new JsonObject(); com.google.gson.JsonArray daily = new com.google.gson.JsonArray(); com.google.gson.JsonArray heatmap = new com.google.gson.JsonArray(); com.google.gson.JsonArray monthly = new com.google.gson.JsonArray();
    YearMonth current = YearMonth.from(today); int monthPlanned = 0, monthCompleted = 0, monthMinutes = 0;
    for (int day = 1; day <= current.lengthOfMonth(); day++) {
      LocalDate date = current.atDay(day); DailyStat stat = values.getOrDefault(date, new DailyStat());
      JsonObject row = new JsonObject(); row.addProperty("day", String.valueOf(day)); row.addProperty("planned", stat.planned); row.addProperty("completed", stat.completed); row.addProperty("minutes", stat.minutes); daily.add(row);
      monthPlanned += stat.planned; monthCompleted += stat.completed; monthMinutes += stat.minutes;
    }
    for (Map.Entry<LocalDate, DailyStat> entry : values.entrySet()) { JsonObject row = new JsonObject(); DailyStat stat = entry.getValue(); row.addProperty("id", entry.getKey().toString()); row.addProperty("value", Math.min(5, stat.completed)); row.addProperty("planned", stat.planned); row.addProperty("completed", stat.completed); row.addProperty("pending", Math.max(0, stat.planned - stat.completed)); row.addProperty("label", entry.getKey().toString()); heatmap.add(row); }
    for (int offset = 5; offset >= 0; offset--) { YearMonth month = current.minusMonths(offset); int planned = 0, completed = 0; for (Map.Entry<LocalDate, DailyStat> entry : values.entrySet()) if (YearMonth.from(entry.getKey()).equals(month)) { planned += entry.getValue().planned; completed += entry.getValue().completed; } JsonObject row = new JsonObject(); row.addProperty("month", month.getMonthValue() + " 月"); row.addProperty("completion", planned == 0 ? 0 : Math.round(completed * 100.0 / planned)); row.addProperty("completed", completed); row.addProperty("delayed", Math.max(0, planned - completed)); monthly.add(row); }
    int streak = 0; for (LocalDate date = today; values.containsKey(date) && values.get(date).completed > 0; date = date.minusDays(1)) streak++;
    JsonObject metrics = new JsonObject(); metrics.addProperty("completion", monthPlanned == 0 ? 0 : Math.round(monthCompleted * 100.0 / monthPlanned)); metrics.addProperty("completed", monthCompleted); metrics.addProperty("planned", monthPlanned); metrics.addProperty("focusHours", Math.round(monthMinutes / 6.0) / 10.0); metrics.addProperty("streak", streak);
    result.add("daily", daily); result.add("heatmap", heatmap); result.add("monthly", monthly); result.add("metrics", metrics); return result;
  }

  private static final class DailyStat { int planned; int completed; int minutes; void add(int p, int c, int m) { planned += p; completed += c; minutes += m; } }

  private void excelExport(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      UUID workspace = workspace(e); List<List<String>> plans = new ArrayList<>(), schedules = new ArrayList<>(), todos = new ArrayList<>();
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement("SELECT title, description, progress, status, due_date FROM plans WHERE workspace_id = ? ORDER BY updated_at DESC")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) plans.add(List.of(rs.getString(1), String.valueOf(rs.getString(2)), rs.getString(3) + "%", rs.getString(4), String.valueOf(rs.getDate(5)))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(start_at), TIME_FORMAT(start_at, '%H:%i'), title, duration_minutes, status FROM schedule_items WHERE workspace_id = ? ORDER BY start_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add(List.of(String.valueOf(rs.getDate(1)), rs.getString(2), rs.getString(3), rs.getString(4) + " 分钟", rs.getString(5))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(due_at), TIME_FORMAT(due_at, '%H:%i'), title, priority, status FROM todos WHERE workspace_id = ? ORDER BY due_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add(List.of(String.valueOf(rs.getDate(1)), String.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5))); } }
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        zipText(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        zipText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        zipText(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"长期计划\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"日程安排\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"一次性待办\" sheetId=\"3\" r:id=\"rId3\"/></sheets></workbook>");
        zipText(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/></Relationships>");
        zipText(zip, "xl/worksheets/sheet1.xml", sheetXml(new String[]{"计划", "说明", "进度", "状态", "截止日期"}, plans));
        zipText(zip, "xl/worksheets/sheet2.xml", sheetXml(new String[]{"日期", "时间", "日程", "时长", "状态"}, schedules));
        zipText(zip, "xl/worksheets/sheet3.xml", sheetXml(new String[]{"日期", "时间", "待办", "优先级", "状态"}, todos));
      }
      e.getResponseHeaders().set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); e.getResponseHeaders().set("Content-Disposition", "attachment; filename=changlu-plan.xlsx"); e.sendResponseHeaders(200, bytes.size()); try (OutputStream out = e.getResponseBody()) { bytes.writeTo(out); }
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void pdfExport(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      UUID workspace = workspace(e);
      List<StatsPdfGenerator.ReportRow> plans = new ArrayList<>(), schedules = new ArrayList<>(), todos = new ArrayList<>();
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement("SELECT title, description, progress, status, due_date FROM plans WHERE workspace_id = ? ORDER BY updated_at DESC")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) plans.add(new StatsPdfGenerator.ReportRow(rs.getString(1), rs.getString(2), rs.getString(3) + "%", rs.getString(4), String.valueOf(rs.getDate(5)))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(start_at), TIME_FORMAT(start_at, '%H:%i'), title, duration_minutes, status FROM schedule_items WHERE workspace_id = ? ORDER BY start_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add(new StatsPdfGenerator.ReportRow(String.valueOf(rs.getDate(1)), rs.getString(2), rs.getString(3), rs.getString(4) + " 分钟", rs.getString(5))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(due_at), TIME_FORMAT(due_at, '%H:%i'), title, priority, status FROM todos WHERE workspace_id = ? ORDER BY due_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add(new StatsPdfGenerator.ReportRow(String.valueOf(rs.getDate(1)), String.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5))); } }
      }
      byte[] bytes = new StatsPdfGenerator().generate(loadStats(workspace), plans, schedules, todos);
      e.getResponseHeaders().set("Content-Type", "application/pdf");
      e.getResponseHeaders().set("Content-Disposition", "attachment; filename=changlu-plan-statistics.pdf");
      e.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void zipText(ZipOutputStream zip, String name, String text) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
  private String sheetXml(String[] headers, List<List<String>> rows) { StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"); int row = 1; xml.append(sheetRow(row++, List.of(headers))); for (List<String> values : rows) xml.append(sheetRow(row++, values)); return xml.append("</sheetData></worksheet>").toString(); }
  private String sheetRow(int row, List<String> values) { StringBuilder xml = new StringBuilder("<row r=\"").append(row).append("\">"); for (int i = 0; i < values.size(); i++) xml.append("<c r=\"").append(column(i + 1)).append(row).append("\" t=\"inlineStr\"><is><t>").append(xmlEscape(values.get(i))).append("</t></is></c>"); return xml.append("</row>").toString(); }
  private String column(int index) { StringBuilder value = new StringBuilder(); while (index > 0) { int remainder = (index - 1) % 26; value.insert(0, (char) ('A' + remainder)); index = (index - 1) / 26; } return value.toString(); }
  private String xmlEscape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }

  private void crud(HttpExchange e, String table) throws IOException {
    try {
      if (options(e)) return;
      String id = pathId(e);
      switch (e.getRequestMethod()) {
        case "GET" -> { if (id == null) list(e, table); else get(e, table, id); }
        case "POST" -> create(e, table);
        case "PUT", "PATCH" -> { requireId(id); update(e, table, id); }
        case "DELETE" -> { requireId(id); delete(e, table, id); }
        default -> json(e, 405, Map.of("error", "method_not_allowed"));
      }
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void notes(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String path = e.getRequestURI().getPath();
      String[] parts = path.split("/");
      if (parts.length >= 5 && "relations".equals(parts[4])) {
        requireId(parts[3]);
        if ("GET".equals(e.getRequestMethod())) listRelations(e, parts[3]);
        else if ("POST".equals(e.getRequestMethod())) createRelation(e, parts[3]);
        else if ("DELETE".equals(e.getRequestMethod())) deleteRelation(e, parts[3]);
        else json(e, 405, Map.of("error", "method_not_allowed"));
        return;
      }
      crud(e, "notes");
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void list(HttpExchange e, String table) throws SQLException, IOException {
    String scope = table.equals("plans") ? "workspace_id = ?" : "workspace_id = ?";
    String sql = "SELECT * FROM " + table + " WHERE " + scope + " ORDER BY updated_at DESC";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) rows.add(row(rs, table)); json(e, 200, rows); }
    }
  }

  private void get(HttpExchange e, String table, String id) throws SQLException, IOException {
    String sql = "SELECT * FROM " + table + " WHERE id = ? AND workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) { json(e, 404, Map.of("error", "not_found")); return; } json(e, 200, row(rs, table)); }
    }
  }

  private void create(HttpExchange e, String table) throws SQLException, IOException {
    JsonObject body = body(e); String id = UUID.randomUUID().toString(); UUID workspace = workspace(e), user = user(e);
    String sql;
    if (table.equals("plans")) sql = "INSERT INTO plans (id, workspace_id, owner_id, title, description, color, status, progress, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else if (table.equals("schedule_items")) sql = "INSERT INTO schedule_items (id, workspace_id, plan_id, created_by, title, description, start_at, duration_minutes, status, progress) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else if (table.equals("todos")) sql = "INSERT INTO todos (id, workspace_id, created_by, title, description, due_at, status, priority, reminder_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else sql = "INSERT INTO notes (id, workspace_id, created_by, category_id, title, excerpt, content, source_type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace));
      if (table.equals("plans")) { p.setBytes(3, Database.uuidBytes(user)); p.setString(4, string(body, "title", "未命名计划")); p.setString(5, nullable(body, "description")); p.setString(6, string(body, "color", "#D39A24")); p.setString(7, string(body, "status", "active")); p.setDouble(8, number(body, "progress", 0)); p.setObject(9, date(body, "dueDate")); }
      else if (table.equals("schedule_items")) { p.setBytes(3, bytesOrNull(body, "planId")); p.setBytes(4, Database.uuidBytes(user)); p.setString(5, string(body, "title", "未命名日程")); p.setString(6, nullable(body, "description")); p.setTimestamp(7, timestamp(body, "startAt")); p.setInt(8, (int) number(body, "durationMinutes", 30)); p.setString(9, string(body, "status", "pending")); p.setDouble(10, number(body, "progress", 0)); }
      else if (table.equals("todos")) { p.setBytes(3, Database.uuidBytes(user)); p.setString(4, string(body, "title", "未命名待办")); p.setString(5, nullable(body, "description")); p.setTimestamp(6, nullableTimestamp(body, "dueAt")); p.setString(7, string(body, "status", "pending")); p.setString(8, string(body, "priority", "medium")); p.setObject(9, integerOrNull(body, "reminderMinutes")); }
      else { p.setBytes(3, Database.uuidBytes(user)); p.setBytes(4, noteCategoryId(workspace, body)); p.setString(5, string(body, "title", "未命名笔记")); p.setString(6, string(body, "excerpt", "")); p.setString(7, string(body, "content", "")); p.setString(8, string(body, "sourceType", "manual")); p.setString(9, string(body, "status", "active")); }
      p.executeUpdate();
    }
    get(e, table, id);
  }

  private void update(HttpExchange e, String table, String id) throws SQLException, IOException {
    JsonObject b = body(e); List<String> columns = new ArrayList<>(); List<Object> values = new ArrayList<>();
    if (table.equals("notes") && b.has("category")) { byte[] category = noteCategoryId(workspace(e), b); if (category != null) b.addProperty("categoryId", Database.bytesUuid(category).toString()); }
    Map<String, String> allowed = table.equals("plans") ? Map.of("title", "title", "description", "description", "color", "color", "status", "status", "progress", "progress", "dueDate", "due_date") : table.equals("schedule_items") ? Map.of("title", "title", "description", "description", "startAt", "start_at", "durationMinutes", "duration_minutes", "status", "status", "progress", "progress", "planId", "plan_id") : table.equals("todos") ? Map.of("title", "title", "description", "description", "dueAt", "due_at", "status", "status", "priority", "priority", "reminderMinutes", "reminder_minutes") : Map.of("title", "title", "excerpt", "excerpt", "content", "content", "status", "status", "categoryId", "category_id");
    for (Map.Entry<String, String> entry : allowed.entrySet()) if (b.has(entry.getKey())) { columns.add(entry.getValue() + " = ?"); values.add(value(b, entry.getKey())); }
    if (columns.isEmpty()) { json(e, 400, Map.of("error", "no_fields")); return; }
    String sql = "UPDATE " + table + " SET " + String.join(", ", columns) + " WHERE id = ? AND workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) { int i = 1; for (Object value : values) p.setObject(i++, value); p.setBytes(i++, Database.uuidBytes(UUID.fromString(id))); p.setBytes(i, Database.uuidBytes(workspace(e))); if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "not_found")); return; } }
    get(e, table, id);
  }

  private void delete(HttpExchange e, String table, String id) throws SQLException, IOException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE id = ? AND workspace_id = ?")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e))); json(e, p.executeUpdate() == 0 ? 404 : 204, Map.of("deleted", true)); } }

  private void listRelations(HttpExchange e, String noteId) throws SQLException, IOException { String sql = "SELECT n.id, n.title, r.relation_type FROM note_relations r JOIN notes n ON n.id = r.to_note_id WHERE r.from_note_id = ? UNION SELECT n.id, n.title, r.relation_type FROM note_relations r JOIN notes n ON n.id = r.from_note_id WHERE r.to_note_id = ?"; try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) { p.setBytes(1, Database.uuidBytes(UUID.fromString(noteId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(noteId))); try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) { JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("title", rs.getString("title")); row.addProperty("relationType", rs.getString("relation_type")); rows.add(row); } json(e, 200, rows); } } }
  private void createRelation(HttpExchange e, String fromId) throws SQLException, IOException { JsonObject b = body(e); String toId = string(b, "toNoteId", null); requireId(toId); try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO note_relations (from_note_id, to_note_id, relation_type) VALUES (?, ?, ?)")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(fromId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(toId))); p.setString(3, string(b, "relationType", "related")); p.executeUpdate(); } json(e, 201, Map.of("fromNoteId", fromId, "toNoteId", toId, "relationType", string(b, "relationType", "related"))); }
  private void deleteRelation(HttpExchange e, String fromId) throws SQLException, IOException { JsonObject b = body(e); requireId(string(b, "toNoteId", null)); try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM note_relations WHERE from_note_id = ? AND to_note_id = ?")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(fromId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(string(b, "toNoteId", null)))); json(e, p.executeUpdate() == 0 ? 404 : 204, Map.of("deleted", true)); } }

  private JsonObject row(ResultSet rs, String table) throws SQLException { JsonObject o = new JsonObject(); o.addProperty("id", Database.id(rs, "id")); o.addProperty("title", rs.getString("title")); if (table.equals("plans")) { o.addProperty("description", rs.getString("description")); o.addProperty("color", rs.getString("color")); o.addProperty("status", rs.getString("status")); o.addProperty("progress", rs.getDouble("progress")); java.sql.Date dueDate = rs.getDate("due_date"); o.addProperty("dueDate", dueDate == null ? null : dueDate.toString()); } else if (table.equals("schedule_items")) { o.addProperty("description", rs.getString("description")); o.addProperty("startAt", String.valueOf(rs.getTimestamp("start_at"))); o.addProperty("durationMinutes", rs.getInt("duration_minutes")); o.addProperty("status", rs.getString("status")); o.addProperty("progress", rs.getDouble("progress")); o.addProperty("planId", rs.getBytes("plan_id") == null ? null : Database.id(rs, "plan_id")); } else if (table.equals("todos")) { o.addProperty("description", rs.getString("description")); Timestamp dueAt = rs.getTimestamp("due_at"); o.addProperty("dueAt", dueAt == null ? null : dueAt.toString()); o.addProperty("status", rs.getString("status")); o.addProperty("priority", rs.getString("priority")); o.addProperty("reminderMinutes", (Integer) rs.getObject("reminder_minutes")); } else { o.addProperty("excerpt", rs.getString("excerpt")); o.addProperty("content", rs.getString("content")); o.addProperty("sourceType", rs.getString("source_type")); o.addProperty("status", rs.getString("status")); byte[] category = rs.getBytes("category_id"); o.addProperty("categoryId", category == null ? null : Database.bytesUuid(category).toString()); o.addProperty("category", category == null ? "未分类" : categoryName(category)); } return o; }

  private byte[] noteCategoryId(UUID workspace, JsonObject body) throws SQLException {
    String category = string(body, "category", null); if (category == null || category.isBlank()) return bytesOrNull(body, "categoryId");
    UUID id = UUID.randomUUID();
    try (Connection c = database.connection(); PreparedStatement insert = c.prepareStatement("INSERT IGNORE INTO note_categories (id, workspace_id, name) VALUES (?, ?, ?)")) { insert.setBytes(1, Database.uuidBytes(id)); insert.setBytes(2, Database.uuidBytes(workspace)); insert.setString(3, category.trim()); insert.executeUpdate(); }
    try (Connection c = database.connection(); PreparedStatement find = c.prepareStatement("SELECT id FROM note_categories WHERE workspace_id = ? AND name = ?")) { find.setBytes(1, Database.uuidBytes(workspace)); find.setString(2, category.trim()); try (ResultSet rs = find.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; } }
  }

  private String categoryName(byte[] id) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT name FROM note_categories WHERE id = ?")) { p.setBytes(1, id); try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : "未分类"; } } }

  private boolean options(HttpExchange e) throws IOException { if (!"OPTIONS".equals(e.getRequestMethod())) return false; cors(e); e.sendResponseHeaders(204, -1); e.close(); return true; }
  private void json(HttpExchange e, int status, Object value) throws IOException { cors(e); byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(status, status == 204 ? -1 : bytes.length); if (status != 204) try (OutputStream out = e.getResponseBody()) { out.write(bytes); } else e.close(); }
  private void cors(HttpExchange e) { Headers h = e.getResponseHeaders(); h.set("Access-Control-Allow-Origin", "*"); h.set("Access-Control-Allow-Headers", "Content-Type, X-Workspace-Id, X-User-Id"); h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS"); }
  private JsonObject body(HttpExchange e) throws IOException { try (InputStream in = e.getRequestBody()) { String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8); return raw.isBlank() ? new JsonObject() : gson.fromJson(raw, JsonObject.class); } }
  private UUID workspace(HttpExchange e) { return uuidHeader(e, "X-Workspace-Id", Database.DEFAULT_WORKSPACE_ID); }
  private UUID user(HttpExchange e) { return uuidHeader(e, "X-User-Id", Database.DEFAULT_USER_ID); }
  private UUID uuidHeader(HttpExchange e, String name, UUID fallback) { String value = e.getRequestHeaders().getFirst(name); return value == null || value.isBlank() ? fallback : UUID.fromString(value); }
  private String pathId(HttpExchange e) { String[] p = e.getRequestURI().getPath().split("/"); return p.length > 3 && !p[3].isBlank() ? p[3] : null; }
  private void requireId(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("id_required"); UUID.fromString(id); }
  private String string(JsonObject o, String key, String fallback) { JsonElement v = o.get(key); return v == null || v.isJsonNull() ? fallback : v.getAsString(); }
  private String nullable(JsonObject o, String key) { return string(o, key, null); }
  private double number(JsonObject o, String key, double fallback) { String v = string(o, key, null); return v == null ? fallback : Double.parseDouble(v); }
  private Object value(JsonObject o, String key) { if (key.endsWith("At")) return nullableTimestamp(o, key); if (key.equals("dueDate")) return date(o, key); if (key.equals("progress")) return number(o, key, 0); if (key.equals("durationMinutes") || key.equals("reminderMinutes")) return integerOrNull(o, key); if (key.equals("planId") || key.equals("categoryId")) return bytesOrNull(o, key); return nullable(o, key); }
  private byte[] bytesOrNull(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : Database.uuidBytes(UUID.fromString(v)); }
  private Integer integerOrNull(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : Integer.valueOf(v); }
  private java.sql.Date date(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : java.sql.Date.valueOf(LocalDate.parse(v)); }
  private Timestamp timestamp(JsonObject o, String key) { String v = string(o, key, null); if (v == null) throw new IllegalArgumentException(key + "_required"); return Timestamp.valueOf(LocalDateTime.parse(v.replace("Z", ""))); }
  private Timestamp nullableTimestamp(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : timestamp(o, key); }
}
