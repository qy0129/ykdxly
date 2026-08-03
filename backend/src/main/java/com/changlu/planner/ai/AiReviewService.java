package com.changlu.planner.ai;

import com.changlu.planner.config.EnvironmentConfig;
import com.changlu.planner.db.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.UUID;

public final class AiReviewService {
  private final Database database;
  private final Gson gson = new Gson();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", "");
  private final String apiUrl = EnvironmentConfig.value("PLANNER_AI_API_URL", "api.url", "https://api.siliconflow.cn/v1/chat/completions");
  private final String model = EnvironmentConfig.value("PLANNER_AI_MODEL", "ai.model", "Qwen/Qwen3.5-9B");

  public AiReviewService(Database database) { this.database = database; }

  public JsonObject chat(JsonObject input, UUID workspaceId, UUID userId) throws Exception {
    if (apiKey.isBlank()) throw new IllegalStateException("PLANNER_AI_API_KEY 未配置");
    String userMessage = required(input, "message");
    UUID conversationId = input.has("conversationId") && !input.get("conversationId").isJsonNull()
        ? UUID.fromString(input.get("conversationId").getAsString()) : UUID.randomUUID();
    if (!ownsConversation(conversationId, workspaceId, userId)) conversationId = UUID.randomUUID();

    JsonArray messages = new JsonArray();
    messages.add(message("system", systemPrompt(loadContext(workspaceId))));
    if (input.has("history") && input.get("history").isJsonArray()) {
      JsonArray history = input.getAsJsonArray("history");
      int start = Math.max(0, history.size() - 12);
      for (int i = start; i < history.size(); i++) {
        JsonObject item = history.get(i).getAsJsonObject();
        String role = item.has("role") ? item.get("role").getAsString() : "user";
        if (!role.equals("user") && !role.equals("assistant")) continue;
        messages.add(message(role, item.has("content") ? item.get("content").getAsString() : ""));
      }
    }
    messages.add(message("user", userMessage));

    JsonObject requestBody = new JsonObject();
    requestBody.addProperty("model", model);
    requestBody.addProperty("temperature", 0.3);
    requestBody.addProperty("max_tokens", 1800);
    requestBody.add("messages", messages);

    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(60))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestBody)))
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) throw new IllegalStateException("AI 服务返回 " + response.statusCode());

    JsonObject payload = JsonParser.parseString(response.body()).getAsJsonObject();
    String content = payload.getAsJsonArray("choices").get(0).getAsJsonObject()
        .getAsJsonObject("message").get("content").getAsString();
    JsonObject result = parseModelResult(content);
    result.addProperty("conversationId", conversationId.toString());
    saveConversation(conversationId, workspaceId, userId, userMessage, result);
    return result;
  }

  private String loadContext(UUID workspaceId) throws Exception {
    JsonObject context = new JsonObject();
    context.add("plans", query("SELECT id, title, description, status, progress, due_date FROM plans WHERE workspace_id = ? ORDER BY updated_at DESC LIMIT 30", workspaceId, "plan"));
    context.add("schedules", query("SELECT id, plan_id, title, start_at, duration_minutes, status, progress FROM schedule_items WHERE workspace_id = ? ORDER BY start_at DESC LIMIT 80", workspaceId, "schedule"));
    context.add("todos", query("SELECT id, title, due_at, status, priority FROM todos WHERE workspace_id = ? ORDER BY due_at DESC LIMIT 50", workspaceId, "todo"));
    return gson.toJson(context);
  }

  private JsonArray query(String sql, UUID workspaceId, String type) throws Exception {
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          JsonObject row = new JsonObject();
          row.addProperty("id", Database.id(rs, "id"));
          row.addProperty("title", rs.getString("title"));
          row.addProperty("status", rs.getString("status"));
          if (type.equals("plan")) {
            row.addProperty("description", rs.getString("description"));
            row.addProperty("progress", rs.getDouble("progress"));
            row.addProperty("dueDate", rs.getDate("due_date") == null ? null : rs.getDate("due_date").toString());
          } else if (type.equals("schedule")) {
            row.addProperty("planId", rs.getBytes("plan_id") == null ? null : Database.id(rs, "plan_id"));
            row.addProperty("startAt", rs.getTimestamp("start_at").toLocalDateTime().toString());
            row.addProperty("durationMinutes", rs.getInt("duration_minutes"));
            row.addProperty("progress", rs.getDouble("progress"));
          } else {
            row.addProperty("dueAt", rs.getTimestamp("due_at") == null ? null : rs.getTimestamp("due_at").toLocalDateTime().toString());
            row.addProperty("priority", rs.getString("priority"));
          }
          rows.add(row);
        }
      }
    }
    return rows;
  }

  private String systemPrompt(String context) {
    return """
        你是长路计划工作台中的 AI 复盘助手。根据用户真实的计划、日程和待办进行复盘，语气清楚、温和、具体。
        你可以讨论执行情况并提出调整，但不能声称已经修改任何数据。只有用户明确表达调整意图时才生成 changes。
        必须只输出一个 JSON 对象，不要 Markdown：
        {"reply":"给用户的中文回复","changes":[{"entity":"plan或schedule","action":"update","id":"现有ID","title":"对象标题","summary":"变更说明","fields":{}}]}
        plan 的 fields 仅允许 title、description、dueDate(yyyy-MM-dd)、progress(0-100)、status(active/paused/completed)。
        schedule 的 fields 仅允许 title、startAt(yyyy-MM-ddTHH:mm:ss)、durationMinutes、status(pending/done/delayed)、progress(0-100)、planId。
        只能引用下方上下文中真实存在的 ID；信息不足时先追问，不生成 changes。最多提出 5 项变更。
        当前数据：
        """ + context;
  }

  private JsonObject parseModelResult(String content) {
    String cleaned = content.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    try {
      JsonObject result = JsonParser.parseString(cleaned).getAsJsonObject();
      if (!result.has("reply")) result.addProperty("reply", "我已经分析了当前计划。你希望先调整哪一部分？");
      if (!result.has("changes") || !result.get("changes").isJsonArray()) result.add("changes", new JsonArray());
      return result;
    } catch (Exception ignored) {
      JsonObject result = new JsonObject();
      result.addProperty("reply", content);
      result.add("changes", new JsonArray());
      return result;
    }
  }

  private void saveConversation(UUID conversationId, UUID workspaceId, UUID userId, String userMessage, JsonObject result) throws Exception {
    try (Connection c = database.connection()) {
      try (PreparedStatement p = c.prepareStatement("INSERT IGNORE INTO ai_conversations (id, workspace_id, user_id) VALUES (?, ?, ?)")) {
        p.setBytes(1, Database.uuidBytes(conversationId)); p.setBytes(2, Database.uuidBytes(workspaceId)); p.setBytes(3, Database.uuidBytes(userId)); p.executeUpdate();
      }
      saveMessage(c, conversationId, "user", userMessage, null);
      saveMessage(c, conversationId, "assistant", result.get("reply").getAsString(), result.get("changes"));
    }
  }

  private boolean ownsConversation(UUID conversationId, UUID workspaceId, UUID userId) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM ai_conversations WHERE id = ? AND workspace_id = ? AND user_id = ?")) {
      p.setBytes(1, Database.uuidBytes(conversationId)); p.setBytes(2, Database.uuidBytes(workspaceId)); p.setBytes(3, Database.uuidBytes(userId));
      try (ResultSet rs = p.executeQuery()) { return rs.next(); }
    }
  }

  private void saveMessage(Connection c, UUID conversationId, String role, String content, JsonElement changes) throws Exception {
    try (PreparedStatement p = c.prepareStatement("INSERT INTO ai_messages (id, conversation_id, role, content, proposed_changes) VALUES (?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(conversationId)); p.setString(3, role); p.setString(4, content); p.setString(5, changes == null ? null : gson.toJson(changes)); p.executeUpdate();
    }
  }

  private JsonObject message(String role, String content) { JsonObject value = new JsonObject(); value.addProperty("role", role); value.addProperty("content", content); return value; }
  private String required(JsonObject input, String name) { if (!input.has(name) || input.get(name).getAsString().isBlank()) throw new IllegalArgumentException(name + "_required"); return input.get(name).getAsString().trim(); }
}
