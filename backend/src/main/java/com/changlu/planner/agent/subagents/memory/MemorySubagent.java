package com.changlu.planner.agent.subagents.memory;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.Subagent;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 负责跨会话长期记忆和单会话滚动摘要，不修改计划业务数据。 */
public final class MemorySubagent implements Subagent {
  private static final Logger LOG = LoggerFactory.getLogger(MemorySubagent.class);
  private static final int SUMMARY_TRIGGER_MESSAGES = 40;
  private static final int SUMMARY_KEEP_MESSAGES = 12;
  private static final Set<String> CATEGORIES = Set.of(
      "preference", "personality", "communication_style", "long_term_goal", "constraint", "personal_fact");

  private final Database database;
  private final ModelClient model;
  private final Gson gson = new Gson();

  public MemorySubagent(Database database, ModelClient model) {
    this.database = database;
    this.model = model;
  }

  @Override public String name() { return "memory"; }
  @Override public String description() { return "读取用户长期偏好、个性和沟通风格，并回答 AI 已记住的内容"; }

  @Override public JsonObject execute(String request, AgentContext context) throws Exception {
    JsonArray memories = list(context.identity());
    JsonObject result = new JsonObject();
    if (memories.isEmpty()) {
      result.addProperty("reply", "我暂时还没有形成长期记忆。你可以直接告诉我你的偏好和习惯，我会在后续对话中使用。 ");
    } else {
      StringBuilder reply = new StringBuilder("我目前记得这些长期信息：\n");
      for (JsonElement element : memories) {
        reply.append("- ").append(element.getAsJsonObject().get("content").getAsString()).append('\n');
      }
      result.addProperty("reply", reply.toString().trim());
    }
    result.add("memories", memories);
    return result;
  }

  /** 每轮对话完成后自动更新长期记忆并按需压缩短期上下文。 */
  public void afterExchange(UUID conversationId, String userMessage, String assistantReply,
                            Database.Context context) {
    try {
      extractAndApply(conversationId, userMessage, assistantReply, context);
    } catch (Exception error) {
      LOG.warn("[长期记忆更新失败] 会话={} 原因={}", conversationId, error.getMessage());
    }
    try {
      compactIfNeeded(conversationId, context);
    } catch (Exception error) {
      LOG.warn("[短期记忆压缩失败] 会话={} 原因={}", conversationId, error.getMessage());
    }
  }

  /** 注入模型的长期记忆，按用户共享而不是按渠道或工作区隔离。 */
  public String context(Database.Context context) throws SQLException {
    JsonArray memories = list(context);
    if (memories.isEmpty()) return "";
    StringBuilder value = new StringBuilder();
    for (JsonElement element : memories) {
      JsonObject memory = element.getAsJsonObject();
      value.append("- [").append(memory.get("category").getAsString()).append("] ")
          .append(memory.get("content").getAsString()).append('\n');
    }
    return value.toString().trim();
  }

  public String conversationSummary(UUID conversationId, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT context_summary FROM ai_conversations WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(conversationId));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("conversation_not_found");
        return rs.getString(1) == null ? "" : rs.getString(1);
      }
    }
  }

  public JsonArray list(Database.Context context) throws SQLException {
    JsonArray result = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id,memory_key,category,content,created_at,updated_at FROM ai_memories "
            + "WHERE user_id=? ORDER BY updated_at DESC,id")) {
      p.setBytes(1, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          JsonObject item = new JsonObject();
          item.addProperty("id", Database.bytesUuid(rs.getBytes("id")).toString());
          item.addProperty("key", rs.getString("memory_key"));
          item.addProperty("category", rs.getString("category"));
          item.addProperty("content", rs.getString("content"));
          item.addProperty("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
          item.addProperty("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
          result.add(item);
        }
      }
    }
    return result;
  }

  public JsonObject update(String reference, JsonObject input, Database.Context context) throws SQLException {
    UUID id = UUID.fromString(reference);
    String content = string(input, "content").trim();
    if (content.isBlank()) throw new IllegalArgumentException("memory_content_required");
    if (content.length() > 2000) throw new IllegalArgumentException("memory_content_too_long");
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE ai_memories SET content=? WHERE id=? AND user_id=?")) {
      p.setString(1, content);
      p.setBytes(2, Database.uuidBytes(id));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      if (p.executeUpdate() == 0) throw new IllegalArgumentException("memory_not_found");
    }
    return memory(id, context);
  }

  public void delete(String reference, Database.Context context) throws SQLException {
    UUID id = UUID.fromString(reference);
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "DELETE FROM ai_memories WHERE id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      if (p.executeUpdate() == 0) throw new IllegalArgumentException("memory_not_found");
    }
  }

  private JsonObject memory(UUID id, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT memory_key,category,content,created_at,updated_at FROM ai_memories WHERE id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("memory_not_found");
        JsonObject item = new JsonObject();
        item.addProperty("id", id.toString());
        item.addProperty("key", rs.getString("memory_key"));
        item.addProperty("category", rs.getString("category"));
        item.addProperty("content", rs.getString("content"));
        item.addProperty("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        item.addProperty("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
        return item;
      }
    }
  }

  private void extractAndApply(UUID conversationId, String userMessage, String assistantReply,
                               Database.Context context) throws Exception {
    if (!model.configured() || userMessage.isBlank()) return;
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的 Memory Subagent，负责从对话中维护跨会话长期记忆。
        自动提取稳定且未来有用的信息：用户偏好、个性、沟通风格、长期目标、长期限制和个人事实。
        不保存临时任务、一次性日期、模型回复中的推测，也不保存密码、令牌、身份证号、银行卡等敏感凭据。
        新信息与旧记忆冲突时使用相同 key 覆盖旧内容。用户明确要求忘记时，把对应 key 放进 deletes。
        key 使用稳定的英文 snake_case；category 只能是 preference、personality、communication_style、long_term_goal、constraint、personal_fact。
        只输出 JSON：{"upserts":[{"key":"communication_tone","category":"communication_style","content":"用户偏好简洁、温和的中文回复"}],"deletes":[]}。
        当前长期记忆：
        """ + context(context)));
    messages.add(ModelClient.message("user", "用户消息：\n" + userMessage + "\n\nAI 回复：\n" + assistantReply));
    JsonObject result = model.completeJson("memory-subagent", messages, 0, 1000, 20, 1);
    JsonArray upserts = array(result, "upserts");
    JsonArray deletes = array(result, "deletes");
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        for (JsonElement element : upserts) {
          if (!element.isJsonObject()) continue;
          JsonObject item = element.getAsJsonObject();
          String key = string(item, "key").trim().toLowerCase();
          String category = string(item, "category").trim();
          String content = string(item, "content").trim();
          if (!key.matches("[a-z0-9_]{1,120}") || !CATEGORIES.contains(category)
              || content.isBlank() || content.length() > 2000) continue;
          try (PreparedStatement p = c.prepareStatement(
              "INSERT INTO ai_memories (id,user_id,memory_key,category,content,source_conversation_id) "
                  + "VALUES (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE category=VALUES(category),content=VALUES(content),"
                  + "source_conversation_id=VALUES(source_conversation_id),updated_at=NOW()")) {
            p.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
            p.setBytes(2, Database.uuidBytes(context.userId()));
            p.setString(3, key);
            p.setString(4, category);
            p.setString(5, content);
            p.setBytes(6, Database.uuidBytes(conversationId));
            p.executeUpdate();
          }
        }
        for (JsonElement element : deletes) {
          if (!element.isJsonPrimitive()) continue;
          try (PreparedStatement p = c.prepareStatement(
              "DELETE FROM ai_memories WHERE user_id=? AND memory_key=?")) {
            p.setBytes(1, Database.uuidBytes(context.userId()));
            p.setString(2, element.getAsString());
            p.executeUpdate();
          }
        }
        c.commit();
      } catch (Exception error) {
        c.rollback();
        throw error;
      } finally {
        c.setAutoCommit(true);
      }
    }
  }

  private void compactIfNeeded(UUID conversationId, Database.Context context) throws Exception {
    SummaryState state = summaryState(conversationId, context);
    if (!model.configured() || state.totalMessages() - state.summarizedMessages() <= SUMMARY_TRIGGER_MESSAGES) return;
    int targetCount = state.totalMessages() - SUMMARY_KEEP_MESSAGES;
    List<String> rows = messages(conversationId, state.summarizedMessages(), targetCount - state.summarizedMessages());
    if (rows.isEmpty()) return;
    JsonArray prompt = new JsonArray();
    prompt.add(ModelClient.message("system", """
        你是长路计划的 Memory Subagent。把旧对话压缩成准确、可继续使用的短期上下文摘要。
        保留用户目标、约束、已确认事实、重要决定、未完成事项和指代关系；不要添加原文没有的信息。
        只输出 JSON：{"summary":"摘要"}。
        已有摘要：
        """ + state.summary()));
    prompt.add(ModelClient.message("user", String.join("\n", rows)));
    JsonObject generated = model.completeJson("memory-summary", prompt, 0, 1600, 25, 1);
    String summary = string(generated, "summary").trim();
    if (summary.isBlank()) return;
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE ai_conversations SET context_summary=?,summarized_message_count=?,"
            + "context_summary_updated_at=NOW() WHERE id=? AND workspace_id=? AND user_id=? "
            + "AND summarized_message_count=?")) {
      p.setString(1, summary);
      p.setInt(2, targetCount);
      p.setBytes(3, Database.uuidBytes(conversationId));
      p.setBytes(4, Database.uuidBytes(context.workspaceId()));
      p.setBytes(5, Database.uuidBytes(context.userId()));
      p.setInt(6, state.summarizedMessages());
      p.executeUpdate();
    }
  }

  private SummaryState summaryState(UUID conversationId, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT a.context_summary,a.summarized_message_count,COUNT(m.id) total_messages "
            + "FROM ai_conversations a LEFT JOIN ai_messages m ON m.conversation_id=a.id "
            + "WHERE a.id=? AND a.workspace_id=? AND a.user_id=? "
            + "GROUP BY a.id,a.context_summary,a.summarized_message_count")) {
      p.setBytes(1, Database.uuidBytes(conversationId));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("conversation_not_found");
        return new SummaryState(rs.getString("context_summary") == null ? "" : rs.getString("context_summary"),
            rs.getInt("summarized_message_count"), rs.getInt("total_messages"));
      }
    }
  }

  private List<String> messages(UUID conversationId, int offset, int limit) throws SQLException {
    List<String> result = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT role,content FROM ai_messages WHERE conversation_id=? ORDER BY created_at,id LIMIT ? OFFSET ?")) {
      p.setBytes(1, Database.uuidBytes(conversationId));
      p.setInt(2, limit);
      p.setInt(3, offset);
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) result.add(rs.getString("role") + "：" + rs.getString("content"));
      }
    }
    return result;
  }

  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
  }

  private String string(JsonObject object, String name) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
  }

  private record SummaryState(String summary, int summarizedMessages, int totalMessages) {}
}
