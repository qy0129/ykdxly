package com.changlu.planner.features.command;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.subagents.memory.MemorySubagent;
import com.changlu.planner.agent.tools.PlanningTools;
import com.changlu.planner.features.plan.PlanExecutionService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI 指令边界。模型只负责把自然语言转换成结构化意图，所有归属、版本、
 * 排期和状态校验都由后端完成，写操作只能在用户确认草案后执行。
 */
public final class AiCommandService {
  private static final Logger LOG = LoggerFactory.getLogger(AiCommandService.class);
  private final Database database;
  private final PlanExecutionService plans;
  private final ModelClient modelClient;
  private final MemorySubagent memory;
  private final Gson gson = new Gson();

  public AiCommandService(Database database) {
    this(database, new ModelClient(), null);
  }

  public AiCommandService(Database database, ModelClient modelClient) {
    this(database, modelClient, null);
  }

  public AiCommandService(Database database, ModelClient modelClient, MemorySubagent memory) {
    this.database = database;
    this.plans = new PlanExecutionService(database);
    this.modelClient = modelClient;
    this.memory = memory == null ? new MemorySubagent(database, modelClient) : memory;
  }

  public JsonObject command(JsonObject input, Database.Context context, String channel) throws Exception {
    if (!modelClient.configured()) throw new IllegalStateException("PLANNER_AI_API_KEY 未配置");
    String text = required(input, "message");
    UUID conversationId = conversation(input, context, channel);
    String modelContext = loadContext(context);
    if (input.has("knowledgeContext") && !input.get("knowledgeContext").isJsonNull()
        && !input.get("knowledgeContext").getAsString().isBlank()) {
      modelContext += "\n与本次请求相关的用户文件资料：\n" + input.get("knowledgeContext").getAsString();
    }
    JsonObject modelResult = ask(text, modelContext, conversationId, context);
    JsonArray actions = array(modelResult, "actions");
    if (actions.isEmpty() && array(modelResult, "questions").isEmpty() && requestsDraft(text)) {
      try {
        JsonObject repaired = ask(text + "\n这是明确的变更请求：必须生成待确认 actions；如果缺少必要信息，改为在 questions 中追问。",
            modelContext, conversationId, context, "planning-agent-repair", 1800, 30, 1);
        if (!array(repaired, "actions").isEmpty() || !array(repaired, "questions").isEmpty()) {
          modelResult = repaired;
          actions = array(modelResult, "actions");
        }
      } catch (Exception error) {
        LOG.warn("[AI草案纠正失败] 会话={} 原因={}", conversationId, error.getMessage());
      }
    }
    if (!requestsScheduling(text)) discardUnrequestedSchedules(actions);

    // 没有可用时段时，任何排期都必须先追问。偏好和排期在同一草案时则允许继续。
    if (containsScheduling(actions) && !plans.preference(context).get("configured").getAsBoolean()
        && !containsAction(actions, "update_preference")) {
      actions = new JsonArray();
      modelResult.addProperty("reply", "排期前还需要你的可用时段，例如：周一到周五 20:00-22:00。");
      JsonArray questions = new JsonArray(); questions.add("你每周哪些时间可以安排任务？");
      modelResult.add("questions", questions);
    }
    validateAndEnrich(actions, context);

    String reply = string(modelResult, "reply", "我已经分析了你的请求。");
    JsonArray questions = array(modelResult, "questions");
    JsonObject result = new JsonObject();
    result.addProperty("conversationId", conversationId.toString());
    result.addProperty("reply", reply);
    result.add("questions", questions);
    result.add("actions", actions);
    saveMessage(conversationId, "user", text, null);
    saveMessage(conversationId, "assistant", reply, actions);
    if (actions.isEmpty()) {
      return result;
    }

    UUID draftId = UUID.randomUUID();
    UUID changeSetId = UUID.randomUUID();
    saveDraft(draftId, changeSetId, context, conversationId, channel, text, reply, actions);
    result.add("draft", draft(draftId, context));
    return result;
  }

  public JsonObject confirm(String draftReference, Database.Context context) throws Exception {
    UUID draftId = draftId(draftReference, context);
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        DraftRow draft = lockedDraft(c, draftId, context);
        if (!"pending".equals(draft.status())) throw new IllegalStateException("草案已处理，当前状态：" + draft.status());
        if (draft.expiresAt().isBefore(LocalDateTime.now())) {
          updateDraftStatus(c, draftId, "expired"); c.commit();
          throw new IllegalStateException("草案已过期，请重新生成");
        }
        JsonArray actions = JsonParser.parseString(draft.actions()).getAsJsonArray();
        // 确认时再次验证版本和归属，避免预览后数据已被其他页面修改。
        validateTargetVersions(c, actions, context);
        JsonArray executed = new JsonArray();
        for (JsonElement item : actions) {
          JsonObject action = item.getAsJsonObject();
          if ("update_preference".equals(required(action, "type"))) plans.savePreference(c, context, fields(action));
        }
        for (JsonElement item : actions) {
          JsonObject action = item.getAsJsonObject();
          if (!"update_preference".equals(required(action, "type"))) {
            executed.addAll(executeAction(c, action, context, draftId, draft.changeSetId(), draft.sourceChannel()));
          }
        }
        updateDraftStatus(c, draftId, "confirmed");
        c.commit();
        JsonObject result = new JsonObject(); result.addProperty("id", draftId.toString());
        result.addProperty("changeSetId", draft.changeSetId().toString()); result.addProperty("status", "confirmed");
        result.add("executed", executed);
        try {
          saveMessage(draft.conversationId(), "assistant", "已确认执行 " + executed.size() + " 项操作。", null);
        } catch (SQLException error) {
          LOG.warn("[AI反馈写入会话失败] 草案={} 原因={}", draftId, error.getMessage());
        }
        return result;
      } catch (Exception error) {
        c.rollback();
        LOG.warn("[AI草案确认失败] 草案={} 原因={}", draftId, error.getMessage(), error);
        throw error;
      }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject cancel(String draftReference, Database.Context context) throws Exception {
    UUID id = draftId(draftReference, context);
    UUID conversationId = conversationIdForDraft(id, context);
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE ai_action_drafts SET status = 'cancelled', cancelled_at = NOW() "
            + "WHERE id = ? AND workspace_id = ? AND user_id = ? AND status = 'pending'")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      if (p.executeUpdate() == 0) throw new IllegalStateException("草案不存在或已经处理");
    }
    JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("status", "cancelled");
    try {
      saveMessage(conversationId, "assistant", "草案已取消，计划数据没有变化。", null);
    } catch (SQLException error) {
      LOG.warn("[AI反馈写入会话失败] 草案={} 原因={}", id, error.getMessage());
    }
    return result;
  }

  public JsonObject draft(String reference, Database.Context context) throws Exception {
    return draft(draftId(reference, context), context);
  }

  /** Agent Runtime 在不同执行器之间复用同一个会话。 */
  public UUID ensureConversation(JsonObject input, Database.Context context, String channel) throws SQLException {
    return conversation(input, context, channel);
  }

  /** 非规划 Subagent 的消息也写入统一的 AI 会话历史。 */
  public void saveExchange(UUID conversationId, String userMessage, String assistantMessage) throws SQLException {
    saveMessage(conversationId, "user", userMessage, null);
    saveMessage(conversationId, "assistant", assistantMessage, null);
  }

  /** 恢复网页或微信最近会话及仍待处理的草案。 */
  public JsonObject session(Database.Context context, String channel) throws SQLException {
    UUID conversationId = recentConversation(context, channel);
    if (conversationId == null) {
      JsonObject result = new JsonObject(); result.add("messages", new JsonArray()); return result;
    }
    return conversationDetail(conversationId.toString(), context, channel);
  }

  public JsonArray conversations(Database.Context context, String channel) throws SQLException {
    JsonArray result = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT c.id,c.title,c.source_channel,c.created_at,c.updated_at,"
            + "(SELECT m.content FROM ai_messages m WHERE m.conversation_id=c.id ORDER BY m.created_at DESC,m.id DESC LIMIT 1) last_message,"
            + "(SELECT COUNT(*) FROM ai_messages m WHERE m.conversation_id=c.id) message_count,"
            + "EXISTS(SELECT 1 FROM ai_action_drafts d WHERE d.conversation_id=c.id AND d.status='pending' AND d.expires_at>NOW()) has_draft,"
            + "(SELECT r.id FROM agent_runs r WHERE r.conversation_id=c.id ORDER BY r.updated_at DESC,r.id DESC LIMIT 1) run_id,"
            + "(SELECT r.status FROM agent_runs r WHERE r.conversation_id=c.id ORDER BY r.updated_at DESC,r.id DESC LIMIT 1) run_status "
            + "FROM ai_conversations c WHERE c.workspace_id=? AND c.user_id=? AND c.source_channel=? "
            + "ORDER BY c.updated_at DESC,c.id DESC LIMIT 100")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      p.setString(3, channel);
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          JsonObject item = new JsonObject();
          item.addProperty("id", Database.bytesUuid(rs.getBytes("id")).toString());
          item.addProperty("title", rs.getString("title"));
          item.addProperty("sourceChannel", rs.getString("source_channel"));
          item.addProperty("lastMessage", rs.getString("last_message"));
          item.addProperty("messageCount", rs.getInt("message_count"));
          item.addProperty("hasPendingDraft", rs.getBoolean("has_draft"));
          byte[] runId = rs.getBytes("run_id");
          if (runId != null) item.addProperty("runId", Database.bytesUuid(runId).toString());
          String runStatus = rs.getString("run_status");
          if (runStatus != null) item.addProperty("runStatus", runStatus);
          item.addProperty("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
          item.addProperty("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
          result.add(item);
        }
      }
    }
    return result;
  }

  public JsonObject createConversation(Database.Context context, String channel) throws SQLException {
    JsonObject input = new JsonObject(); input.addProperty("newConversation", true);
    UUID id = conversation(input, context, channel);
    return conversationDetail(id.toString(), context, channel);
  }

  public JsonObject conversationDetail(String reference, Database.Context context, String channel)
      throws SQLException {
    UUID id = UUID.fromString(reference); requireConversationOwner(id, context); activateConversation(id, context, channel);
    JsonObject result = new JsonObject();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT title,source_channel,context_summary,created_at,updated_at FROM ai_conversations WHERE id=?")) {
      p.setBytes(1, Database.uuidBytes(id));
      try (ResultSet rs = p.executeQuery()) {
        rs.next();
        result.addProperty("conversationId", id.toString());
        result.addProperty("title", rs.getString("title"));
        result.addProperty("sourceChannel", rs.getString("source_channel"));
        result.addProperty("contextSummary", rs.getString("context_summary"));
        result.addProperty("createdAt", rs.getTimestamp("created_at").toLocalDateTime().toString());
        result.addProperty("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime().toString());
      }
    }
    result.add("messages", historyPayload(id, context, 0));
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id FROM ai_action_drafts WHERE conversation_id=? AND workspace_id=? AND user_id=? "
            + "AND status='pending' AND expires_at>NOW() ORDER BY created_at DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) result.add("draft", draft(Database.bytesUuid(rs.getBytes(1)), context));
      }
    }
    return result;
  }

  public JsonObject renameConversation(String reference, JsonObject input, Database.Context context)
      throws SQLException {
    UUID id = UUID.fromString(reference); requireConversationOwner(id, context);
    String title = required(input, "title").trim();
    if (title.length() > 80) title = title.substring(0, 80);
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE ai_conversations SET title=? WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setString(1, title); p.setBytes(2, Database.uuidBytes(id));
      p.setBytes(3, Database.uuidBytes(context.workspaceId())); p.setBytes(4, Database.uuidBytes(context.userId()));
      p.executeUpdate();
    }
    JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("title", title);
    return result;
  }

  public void deleteConversation(String reference, Database.Context context) throws SQLException {
    UUID id = UUID.fromString(reference); requireConversationOwner(id, context);
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement drafts = c.prepareStatement("DELETE FROM ai_action_drafts WHERE conversation_id=?")) {
          drafts.setBytes(1, Database.uuidBytes(id)); drafts.executeUpdate();
        }
        try (PreparedStatement conversation = c.prepareStatement(
            "DELETE FROM ai_conversations WHERE id=? AND workspace_id=? AND user_id=?")) {
          conversation.setBytes(1, Database.uuidBytes(id));
          conversation.setBytes(2, Database.uuidBytes(context.workspaceId()));
          conversation.setBytes(3, Database.uuidBytes(context.userId()));
          conversation.executeUpdate();
        }
        c.commit();
      } catch (Exception error) {
        c.rollback(); throw error;
      } finally { c.setAutoCommit(true); }
    }
  }

  /** 仅当变更集中的实体都没有再次修改时，才允许整组撤销。 */
  public JsonObject undo(String changeSetReference, Database.Context context) throws SQLException {
    UUID changeSetId = UUID.fromString(changeSetReference);
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        List<ExecutionRow> records = new ArrayList<>();
        try (PreparedStatement p = c.prepareStatement(
            "SELECT id, entity_type, entity_id, before_snapshot, version_after FROM execution_records "
                + "WHERE change_set_id = ? AND workspace_id = ? AND user_id = ? AND undone_at IS NULL ORDER BY occurred_at DESC, created_at DESC FOR UPDATE")) {
          p.setBytes(1, Database.uuidBytes(changeSetId)); p.setBytes(2, Database.uuidBytes(context.workspaceId()));
          p.setBytes(3, Database.uuidBytes(context.userId()));
          try (ResultSet rs = p.executeQuery()) { while (rs.next()) records.add(new ExecutionRow(
              Database.bytesUuid(rs.getBytes(1)), rs.getString(2), Database.bytesUuid(rs.getBytes(3)),
              rs.getString(4), integer(rs.getObject(5)))); }
        }
        if (records.isEmpty()) throw new IllegalArgumentException("变更集不存在或已经撤销");
        for (ExecutionRow record : records) undoRecord(c, record, context);
        try (PreparedStatement p = c.prepareStatement("UPDATE execution_records SET undone_at = NOW() WHERE change_set_id = ? AND workspace_id = ? AND user_id = ?")) {
          p.setBytes(1, Database.uuidBytes(changeSetId)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("UPDATE ai_action_drafts SET undone_at = NOW() WHERE change_set_id = ? AND workspace_id = ? AND user_id = ?")) {
          p.setBytes(1, Database.uuidBytes(changeSetId)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.executeUpdate();
        }
        c.commit(); JsonObject result = new JsonObject(); result.addProperty("changeSetId", changeSetId.toString());
        result.addProperty("status", "undone"); result.addProperty("restored", records.size());
        return result;
      } catch (Exception error) {
        c.rollback();
        LOG.warn("[AI变更撤销失败] 变更集={} 原因={}", changeSetId, error.getMessage(), error);
        throw error;
      }
      finally { c.setAutoCommit(true); }
    }
  }

  public JsonObject reviewFacts(Database.Context context) throws SQLException {
    JsonObject facts = new JsonObject(); facts.addProperty("date", LocalDate.now().toString());
    facts.addProperty("completedTasks", count("SELECT COUNT(*) FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.status='done' AND DATE(t.completed_at)=CURDATE() AND t.deleted_at IS NULL", context));
    facts.addProperty("completed", count("SELECT COUNT(*) FROM todos WHERE workspace_id=? AND status='done' AND DATE(completed_at)=CURDATE() AND deleted_at IS NULL", context));
    facts.addProperty("scheduleCompleted", count("SELECT COUNT(*) FROM schedule_items WHERE workspace_id=? AND status='done' AND DATE(completed_at)=CURDATE() AND deleted_at IS NULL", context));
    facts.addProperty("delayed", countRecords(context, "delay_%"));
    facts.addProperty("blocked", countRecords(context, "block_%"));
    facts.addProperty("focusMinutes", sumMinutes(context));
    facts.addProperty("estimationError7d", estimationError(context));
    JsonArray logs = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT entity_type, action_type, note, actual_minutes, occurred_at FROM execution_records "
            + "WHERE workspace_id=? AND user_id=? AND DATE(occurred_at)=CURDATE() AND undone_at IS NULL ORDER BY occurred_at DESC LIMIT 40")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) {
        JsonObject row = new JsonObject(); row.addProperty("entityType", rs.getString(1)); row.addProperty("action", rs.getString(2));
        row.addProperty("note", rs.getString(3)); row.addProperty("actualMinutes", integer(rs.getObject(4)));
        row.addProperty("occurredAt", rs.getTimestamp(5).toLocalDateTime().toString()); logs.add(row);
      }}
    }
    facts.add("logs", logs); saveReviewFacts(context, facts); return facts;
  }

  private JsonObject ask(String userText, String context, UUID conversationId, Database.Context owner) throws Exception {
    return ask(userText, context, conversationId, owner, "planning-agent", 5000, 60, 2);
  }

  private JsonObject ask(String userText, String context, UUID conversationId, Database.Context owner,
                         String purpose, int maxTokens, int timeoutSeconds, int maxAttempts) throws Exception {
    JsonArray messages = new JsonArray(); messages.add(message("system", systemPrompt(context)));
    messages.addAll(historyForModel(conversationId, owner)); messages.add(message("user", userText));
    return modelClient.completeJson(purpose, messages, 0.1, maxTokens, timeoutSeconds, maxAttempts);
  }

  private String systemPrompt(String context) {
    return """
        你是个人规划助手。模型只能输出意图，不能声称已经执行。只输出 JSON：
        {"reply":"中文回复","questions":[],"actions":[{"type":"动作","summary":"影响说明","targetId":"更新对象ID","fields":{}}]}。
        不要输出 version 或 expectedVersion，版本号由服务端在确认前读取和校验。
        信息不足时 questions 给出具体问题且 actions 必须为空。dueDate 只用 yyyy-MM-dd；dueAt、startAt 用 yyyy-MM-ddTHH:mm:ss。
        计划闭环是 Plan -> Stage -> PlanTask -> CalendarEvent。Todo 只表示与计划无关的一次性事项。
        允许动作：create_plan/update_plan/delete_plan/restore_plan，create_stage/update_stage/delete_stage/restore_stage，
        create_task/update_task/complete_task/delay_task/block_task/skip_task/cancel_task/delete_task/restore_task，
        create_todo/update_todo/complete_todo/delay_todo/delete_todo/restore_todo，
        create_schedule/update_schedule/complete_schedule/delay_schedule/delete_schedule/restore_schedule，batch_reschedule、update_preference。
        create_plan fields 可包含 stages:[{title,dueDate,tasks:[{title,description,priority,estimatedMinutes,dueAt,schedules:[{title,startAt,durationMinutes}]}]}]。
        用户明确要求创建、布置或安排时，不能只在 reply 中声称已生成草案：必须返回 actions；没有现成计划时使用 create_plan 嵌套阶段和任务。
        “布置任务”和截止时间不等于排期；只有用户明确要求排期、日程或时间块时才能生成 schedule。
        create_stage 必须带 planId；create_task 必须带 planId、stageId；create_schedule 应带 taskId，且必须有 startAt、durationMinutes。
        update_preference fields：timezone、availability，availability 示例 {"monday":[{"start":"20:00","end":"22:00"}]}。
        complete_task 可带 actualMinutes、reason；delay_task 必须给 dueAt；block_task 必须给 reason。
        完成日程只代表时间块结束，不能代替完成任务。删除和批量调整必须列出具体影响项。
        只能引用下面真实存在的 ID；查询和复盘直接根据真实数据回答，actions 为空。
        长期记忆中记录了用户稳定的偏好和沟通风格。相关时自然遵循，不要主动声称“我记得”。
        当前真实上下文：
        """ + context;
  }

  private String loadContext(Database.Context context) throws SQLException {
    JsonObject value = new JsonObject(); JsonObject preference = plans.preference(context);
    String timezone = string(preference, "timezone", "Asia/Shanghai");
    value.addProperty("currentTime", LocalDateTime.now(ZoneId.of(timezone)).toString()); value.addProperty("timezone", timezone);
    value.add("preference", preference);
    value.addProperty("longTermMemory", memory.context(context));
    value.add("plans", query("SELECT id,title,description,status,progress,task_progress,effort_progress,due_date,version FROM plans WHERE workspace_id=? AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 30", context.workspaceId(), "plan"));
    value.add("stages", query("SELECT s.id,s.plan_id,s.title,s.status,s.progress,s.task_progress,s.effort_progress,s.due_date,s.version FROM plan_stages s JOIN plans p ON p.id=s.plan_id WHERE p.workspace_id=? AND s.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY s.plan_id,s.sort_order", context.workspaceId(), "stage"));
    value.add("tasks", query("SELECT t.id,t.plan_id,t.stage_id,t.title,t.status,t.priority,t.estimated_minutes,t.actual_minutes,t.due_at,t.version FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY t.due_at LIMIT 100", context.workspaceId(), "task"));
    value.add("todos", query("SELECT id,title,status,priority,due_at,version FROM todos WHERE workspace_id=? AND deleted_at IS NULL ORDER BY due_at LIMIT 60", context.workspaceId(), "todo"));
    value.add("schedules", query("SELECT id,title,status,start_at,duration_minutes,plan_id,stage_id,task_id,version FROM schedule_items WHERE workspace_id=? AND deleted_at IS NULL ORDER BY start_at DESC LIMIT 100", context.workspaceId(), "schedule"));
    value.add("recentExecution", recentExecution(context)); return gson.toJson(value);
  }

  private JsonArray query(String sql, UUID workspaceId, String type) throws SQLException {
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) {
        JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("title", rs.getString("title")); row.addProperty("status", rs.getString("status"));
        if ("plan".equals(type)) { row.addProperty("description", rs.getString("description")); addProgress(row, rs); row.addProperty("dueDate", dateText(rs, "due_date")); row.addProperty("version", rs.getInt("version")); }
        if ("stage".equals(type)) { row.addProperty("planId", Database.id(rs, "plan_id")); addProgress(row, rs); row.addProperty("dueDate", dateText(rs, "due_date")); row.addProperty("version", rs.getInt("version")); }
        if ("task".equals(type)) { row.addProperty("planId", Database.id(rs, "plan_id")); row.addProperty("stageId", Database.id(rs, "stage_id")); row.addProperty("priority", rs.getString("priority")); row.addProperty("estimatedMinutes", integer(rs.getObject("estimated_minutes"))); row.addProperty("actualMinutes", integer(rs.getObject("actual_minutes"))); row.addProperty("dueAt", timeText(rs, "due_at")); row.addProperty("version", rs.getInt("version")); }
        if ("todo".equals(type)) { row.addProperty("priority", rs.getString("priority")); row.addProperty("dueAt", timeText(rs, "due_at")); row.addProperty("version", rs.getInt("version")); }
        if ("schedule".equals(type)) { row.addProperty("startAt", timeText(rs, "start_at")); row.addProperty("durationMinutes", rs.getInt("duration_minutes")); addUuid(row, "planId", rs.getBytes("plan_id")); addUuid(row, "stageId", rs.getBytes("stage_id")); addUuid(row, "taskId", rs.getBytes("task_id")); row.addProperty("version", rs.getInt("version")); }
        rows.add(row);
      }}
    }
    return rows;
  }

  private void validateAndEnrich(JsonArray actions, Database.Context context) throws SQLException {
    if (actions.size() > 50) throw new IllegalArgumentException("一次最多生成 50 项操作");
    try (Connection c = database.connection()) {
      for (JsonElement item : actions) {
        if (!item.isJsonObject()) throw new IllegalArgumentException("AI 操作格式无效");
        JsonObject action = item.getAsJsonObject(); String type = required(action, "type");
        if (!PlanningTools.ACTION_TYPES.contains(type)) throw new IllegalArgumentException("AI 返回了不支持的操作：" + type);
        // version 只能来自服务端快照。模型偶尔会复制上下文里的 version，不能因此拒绝整个草案。
        action.remove("version");
        requireOnly(action, List.of("type", "summary", "targetId", "fields", "expectedVersion"));
        JsonObject fields = fields(action);
        normalizeModelFields(fields);
        if (type.startsWith("create_") && !"create_schedule".equals(type) && string(fields, "title", "").isBlank()) throw new IllegalArgumentException("创建操作缺少标题");
        if (needsTarget(type)) {
          UUID targetId = UUID.fromString(required(action, "targetId")); JsonObject before = target(c, context, type, targetId, type.startsWith("restore_"));
          action.add("before", before); action.addProperty("expectedVersion", before.get("version").getAsInt());
          fields.addProperty("expectedVersion", before.get("version").getAsInt()); action.add("changes", changes(before, fields));
        } else {
          action.add("before", JsonNull.INSTANCE); action.add("changes", changes(null, fields));
        }
        validateFields(type, fields);
      }
    }
  }

  private void validateTargetVersions(Connection c, JsonArray actions, Database.Context context) throws SQLException {
    for (JsonElement item : actions) {
      JsonObject action = item.getAsJsonObject(); String type = required(action, "type");
      if (!needsTarget(type)) continue;
      JsonObject current = target(c, context, type, UUID.fromString(required(action, "targetId")), type.startsWith("restore_"));
      int expected = action.get("expectedVersion").getAsInt();
      if (current.get("version").getAsInt() != expected) throw new IllegalStateException("version_conflict:" + required(action, "targetId"));
    }
  }

  private JsonArray executeAction(Connection c, JsonObject action, Database.Context context, UUID draftId,
                                  UUID changeSetId, String source) throws SQLException {
    String type = required(action, "type"); JsonObject f = fields(action); JsonArray result = new JsonArray();
    switch (type) {
      case "create_plan" -> result.addAll(createPlan(c, f, context, draftId, changeSetId, source));
      case "update_plan" -> result.add(item(type, updatePlan(c, UUID.fromString(required(action, "targetId")), f, context, draftId, changeSetId, source), action));
      case "delete_plan", "restore_plan", "delete_stage", "restore_stage", "delete_todo", "restore_todo", "delete_schedule", "restore_schedule" ->
          result.add(item(type, softDeleteOrRestore(c, type, UUID.fromString(required(action, "targetId")), f, context, draftId, changeSetId, source), action));
      case "create_stage" -> result.add(item(type, plans.createStage(c, context, UUID.fromString(required(f, "planId")), f, draftId, changeSetId, source).get("id").getAsString(), action));
      case "update_stage" -> result.add(item(type, plans.updateStage(c, context, UUID.fromString(required(action, "targetId")), f, draftId, changeSetId, source).get("id").getAsString(), action));
      case "create_task" -> result.add(item(type, plans.createTask(c, context, UUID.fromString(required(f, "planId")), f, draftId, changeSetId, source).get("id").getAsString(), action));
      case "update_task", "complete_task", "delay_task", "block_task", "skip_task", "cancel_task" -> {
        prepareTaskStatus(type, f); result.add(item(type, plans.updateTask(c, context, UUID.fromString(required(action, "targetId")), f, draftId, changeSetId, source).get("id").getAsString(), action));
      }
      case "delete_task" -> result.add(item(type, softDeleteOrRestore(c, type, UUID.fromString(required(action, "targetId")), f, context, draftId, changeSetId, source), action));
      case "restore_task" -> result.add(item(type, softDeleteOrRestore(c, type, UUID.fromString(required(action, "targetId")), f, context, draftId, changeSetId, source), action));
      case "create_todo" -> result.add(item(type, createTodo(c, f, context, draftId, changeSetId, source), action));
      case "update_todo", "complete_todo", "delay_todo" -> result.add(item(type, updateTodo(c, type, UUID.fromString(required(action, "targetId")), f, context, draftId, changeSetId, source), action));
      case "create_schedule" -> result.add(item(type, plans.createSchedule(c, context, f, draftId, changeSetId, source, true).get("id").getAsString(), action));
      case "update_schedule", "complete_schedule", "delay_schedule" -> {
        prepareScheduleStatus(type, f); result.add(item(type, plans.updateSchedule(c, context, UUID.fromString(required(action, "targetId")), f, draftId, changeSetId, source, true).get("id").getAsString(), action));
      }
      case "batch_reschedule" -> {
        for (JsonElement entry : array(f, "items")) {
          JsonObject schedule = entry.getAsJsonObject(); JsonObject created = plans.createSchedule(c, context, schedule, draftId, changeSetId, source, true);
          result.add(item("create_schedule", created.get("id").getAsString(), action));
        }
      }
      default -> throw new IllegalArgumentException("不支持的操作：" + type);
    }
    return result;
  }

  private JsonArray createPlan(Connection c, JsonObject f, Database.Context context, UUID draftId, UUID changeSetId, String source) throws SQLException {
    UUID planId = UUID.randomUUID();
    try (PreparedStatement p = c.prepareStatement("INSERT INTO plans (id,workspace_id,owner_id,title,description,color,due_date) VALUES (?,?,?,?,?,?,?)")) {
      p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      p.setString(4, required(f, "title")); p.setString(5, string(f, "description", "")); p.setString(6, string(f, "color", "#D39A24")); p.setObject(7, date(f, "dueDate")); p.executeUpdate();
    }
    JsonObject planAfter = plan(c, context.workspaceId(), planId, false);
    record(c, context, draftId, changeSetId, "plan", planId, "create_plan", null, planAfter, string(f, "reason", "创建计划"), source, null, planAfter.get("version").getAsInt());
    JsonArray executed = new JsonArray(); JsonObject planAction = new JsonObject(); planAction.addProperty("type", "create_plan"); planAction.addProperty("summary", "创建计划：" + required(f, "title"));
    executed.add(item("create_plan", planId.toString(), planAction));
    for (JsonElement stageElement : array(f, "stages")) {
      JsonObject sf = stageElement.getAsJsonObject(); sf.addProperty("planId", planId.toString());
      JsonObject stage = plans.createStage(c, context, planId, sf, draftId, changeSetId, source); UUID stageId = UUID.fromString(stage.get("id").getAsString());
      executed.add(simpleItem("create_stage", stageId, "创建阶段：" + required(sf, "title")));
      for (JsonElement taskElement : array(sf, "tasks")) {
        JsonObject tf = taskElement.getAsJsonObject(); tf.addProperty("planId", planId.toString()); tf.addProperty("stageId", stageId.toString());
        JsonObject task = plans.createTask(c, context, planId, tf, draftId, changeSetId, source); UUID taskId = UUID.fromString(task.get("id").getAsString());
        executed.add(simpleItem("create_task", taskId, "创建任务：" + required(tf, "title")));
        for (JsonElement scheduleElement : array(tf, "schedules")) {
          JsonObject schedule = scheduleElement.getAsJsonObject(); schedule.addProperty("planId", planId.toString()); schedule.addProperty("stageId", stageId.toString()); schedule.addProperty("taskId", taskId.toString());
          if (!schedule.has("title")) schedule.addProperty("title", required(tf, "title"));
          JsonObject created = plans.createSchedule(c, context, schedule, draftId, changeSetId, source, true);
          executed.add(simpleItem("create_schedule", UUID.fromString(created.get("id").getAsString()), "安排任务：" + required(tf, "title")));
        }
      }
    }
    return executed;
  }

  private String updatePlan(Connection c, UUID id, JsonObject f, Database.Context context, UUID draftId, UUID changeSetId, String source) throws SQLException {
    JsonObject before = plan(c, context.workspaceId(), id, false); int expected = integer(f, "expectedVersion", before.get("version").getAsInt());
    try (PreparedStatement p = c.prepareStatement("UPDATE plans SET title=COALESCE(?,title),description=COALESCE(?,description),color=COALESCE(?,color),due_date=COALESCE(?,due_date),status=COALESCE(?,status),version=version+1 WHERE id=? AND workspace_id=? AND version=? AND deleted_at IS NULL")) {
      p.setString(1, nullable(f, "title")); p.setString(2, nullable(f, "description")); p.setString(3, nullable(f, "color")); p.setObject(4, date(f, "dueDate")); p.setString(5, nullable(f, "status")); p.setBytes(6, Database.uuidBytes(id)); p.setBytes(7, Database.uuidBytes(context.workspaceId())); p.setInt(8, expected);
      requireAffected(p.executeUpdate(), "plan_version_conflict");
    }
    JsonObject after = plan(c, context.workspaceId(), id, false); record(c, context, draftId, changeSetId, "plan", id, "update_plan", before, after, string(f, "reason", "调整计划"), source, null, after.get("version").getAsInt()); return id.toString();
  }

  private String createTodo(Connection c, JsonObject f, Database.Context context, UUID draftId, UUID changeSetId, String source) throws SQLException {
    UUID id = UUID.randomUUID();
    try (PreparedStatement p = c.prepareStatement("INSERT INTO todos (id,workspace_id,created_by,title,description,due_at,priority) VALUES (?,?,?,?,?,?,?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, required(f, "title")); p.setString(5, nullable(f, "description")); p.setObject(6, timestamp(f, "dueAt")); p.setString(7, priority(f)); p.executeUpdate();
    }
    JsonObject after = todo(c, context.workspaceId(), id, false); record(c, context, draftId, changeSetId, "todo", id, "create_todo", null, after, string(f, "reason", "创建独立待办"), source, null, after.get("version").getAsInt()); return id.toString();
  }

  private String updateTodo(Connection c, String type, UUID id, JsonObject f, Database.Context context, UUID draftId, UUID changeSetId, String source) throws SQLException {
    JsonObject before = todo(c, context.workspaceId(), id, false); int expected = integer(f, "expectedVersion", before.get("version").getAsInt());
    String status = "complete_todo".equals(type) ? "done" : "delay_todo".equals(type) ? "pending" : nullable(f, "status");
    if ("done".equals(before.get("status").getAsString()) && "done".equals(status)) return id.toString();
    try (PreparedStatement p = c.prepareStatement("UPDATE todos SET title=COALESCE(?,title),description=COALESCE(?,description),due_at=COALESCE(?,due_at),priority=COALESCE(?,priority),status=COALESCE(?,status),completed_at=CASE WHEN ?='done' THEN NOW() WHEN ? IS NOT NULL AND ?<>'done' THEN NULL ELSE completed_at END,version=version+1 WHERE id=? AND workspace_id=? AND version=? AND deleted_at IS NULL")) {
      p.setString(1, nullable(f, "title")); p.setString(2, nullable(f, "description")); p.setObject(3, timestamp(f, "dueAt")); p.setString(4, nullable(f, "priority")); p.setString(5, status); p.setString(6, status); p.setString(7, status); p.setString(8, status); p.setBytes(9, Database.uuidBytes(id)); p.setBytes(10, Database.uuidBytes(context.workspaceId())); p.setInt(11, expected); requireAffected(p.executeUpdate(), "todo_version_conflict");
    }
    JsonObject after = todo(c, context.workspaceId(), id, false); record(c, context, draftId, changeSetId, "todo", id, type, before, after, string(f, "reason", type), source, optionalInteger(f, "actualMinutes"), after.get("version").getAsInt()); return id.toString();
  }

  private String softDeleteOrRestore(Connection c, String type, UUID id, JsonObject f, Database.Context context, UUID draftId, UUID changeSetId, String source) throws SQLException {
    EntityTable entity = entityTable(type); boolean restore = type.startsWith("restore_"); JsonObject before = snapshot(c, entity.entityType(), entity.table(), context.workspaceId(), id, restore);
    int expected = integer(f, "expectedVersion", before.get("version").getAsInt());
    String sql = restore
        ? "UPDATE " + entity.table() + " SET deleted_at=NULL,purge_after=NULL,version=version+1 WHERE id=? AND " + entity.workspaceClause() + " AND version=? AND deleted_at IS NOT NULL"
        : "UPDATE " + entity.table() + " SET deleted_at=NOW(),purge_after=DATE_ADD(NOW(),INTERVAL 30 DAY),version=version+1 WHERE id=? AND " + entity.workspaceClause() + " AND version=? AND deleted_at IS NULL";
    try (PreparedStatement p = c.prepareStatement(sql)) { p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setInt(3, expected); requireAffected(p.executeUpdate(), entity.entityType() + "_version_conflict"); }
    JsonObject after = snapshot(c, entity.entityType(), entity.table(), context.workspaceId(), id, true);
    record(c, context, draftId, changeSetId, entity.entityType(), id, type, before, after, string(f, "reason", restore ? "从回收站恢复" : "移入回收站"), source, null, after.get("version").getAsInt());
    UUID planId = planIdFromSnapshot(before); if (planId != null) plans.recomputeProgress(c, planId); return id.toString();
  }

  private void prepareTaskStatus(String type, JsonObject f) {
    String status = switch (type) { case "complete_task" -> "done"; case "delay_task" -> "pending"; case "block_task" -> "blocked"; case "skip_task" -> "skipped"; case "cancel_task" -> "cancelled"; default -> null; };
    if (status != null) f.addProperty("status", status); f.addProperty("actionType", type);
  }

  private void prepareScheduleStatus(String type, JsonObject f) {
    if ("complete_schedule".equals(type)) f.addProperty("status", "done");
    if ("delay_schedule".equals(type)) f.addProperty("status", "delayed");
    f.addProperty("actionType", type);
  }

  private JsonObject target(Connection c, Database.Context context, String type, UUID id, boolean includeDeleted) throws SQLException {
    EntityTable entity = entityTable(type); return snapshot(c, entity.entityType(), entity.table(), context.workspaceId(), id, includeDeleted);
  }

  private JsonObject snapshot(Connection c, String entityType, String table, UUID workspaceId, UUID id, boolean includeDeleted) throws SQLException {
    String sql = ("plan_stage".equals(entityType) || "plan_task".equals(entityType))
        ? "SELECT x.* FROM " + table + " x JOIN plans p ON p.id=x.plan_id WHERE x.id=? AND p.workspace_id=?" + (includeDeleted ? "" : " AND x.deleted_at IS NULL AND p.deleted_at IS NULL")
        : "SELECT * FROM " + table + " WHERE id=? AND workspace_id=?" + (includeDeleted ? "" : " AND deleted_at IS NULL");
    try (PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException(entityType + "_not_found"); return switch (entityType) {
        case "plan" -> planRow(rs); case "plan_stage" -> stageRow(rs); case "plan_task" -> taskRow(rs); case "todo" -> todoRow(rs); case "schedule" -> scheduleRow(rs); default -> throw new IllegalArgumentException("unknown_entity");
      }; }
    }
  }

  private JsonObject plan(Connection c, UUID workspaceId, UUID id, boolean includeDeleted) throws SQLException { return snapshot(c, "plan", "plans", workspaceId, id, includeDeleted); }
  private JsonObject todo(Connection c, UUID workspaceId, UUID id, boolean includeDeleted) throws SQLException { return snapshot(c, "todo", "todos", workspaceId, id, includeDeleted); }

  private JsonObject planRow(ResultSet rs) throws SQLException {
    JsonObject o = baseRow(rs); o.addProperty("description", rs.getString("description")); o.addProperty("color", rs.getString("color")); o.addProperty("status", rs.getString("status")); addProgress(o, rs); o.addProperty("dueDate", dateText(rs, "due_date")); return o;
  }
  private JsonObject stageRow(ResultSet rs) throws SQLException {
    JsonObject o = baseRow(rs); o.addProperty("planId", Database.id(rs, "plan_id")); o.addProperty("description", rs.getString("description")); o.addProperty("status", rs.getString("status")); addProgress(o, rs); o.addProperty("dueDate", dateText(rs, "due_date")); o.addProperty("sortOrder", rs.getInt("sort_order")); return o;
  }
  private JsonObject taskRow(ResultSet rs) throws SQLException {
    JsonObject o = baseRow(rs); o.addProperty("planId", Database.id(rs, "plan_id")); o.addProperty("stageId", Database.id(rs, "stage_id")); o.addProperty("description", rs.getString("description")); o.addProperty("status", rs.getString("status")); o.addProperty("priority", rs.getString("priority")); o.addProperty("estimatedMinutes", integer(rs.getObject("estimated_minutes"))); o.addProperty("actualMinutes", integer(rs.getObject("actual_minutes"))); o.addProperty("dueAt", timeText(rs, "due_at")); o.addProperty("completedAt", timeText(rs, "completed_at")); o.addProperty("reason", rs.getString("blocked_reason")); o.addProperty("sortOrder", rs.getInt("sort_order")); return o;
  }
  private JsonObject todoRow(ResultSet rs) throws SQLException {
    JsonObject o = baseRow(rs); o.addProperty("description", rs.getString("description")); o.addProperty("status", rs.getString("status")); o.addProperty("priority", rs.getString("priority")); o.addProperty("dueAt", timeText(rs, "due_at")); o.addProperty("completedAt", timeText(rs, "completed_at")); return o;
  }
  private JsonObject scheduleRow(ResultSet rs) throws SQLException {
    JsonObject o = baseRow(rs); o.addProperty("description", rs.getString("description")); o.addProperty("status", rs.getString("status")); o.addProperty("startAt", timeText(rs, "start_at")); o.addProperty("durationMinutes", rs.getInt("duration_minutes")); addUuid(o, "planId", rs.getBytes("plan_id")); addUuid(o, "stageId", rs.getBytes("stage_id")); addUuid(o, "taskId", rs.getBytes("task_id")); o.addProperty("completedAt", timeText(rs, "completed_at")); return o;
  }
  private JsonObject baseRow(ResultSet rs) throws SQLException { JsonObject o = new JsonObject(); o.addProperty("id", Database.id(rs, "id")); o.addProperty("title", rs.getString("title")); o.addProperty("version", rs.getInt("version")); o.addProperty("deletedAt", timeText(rs, "deleted_at")); return o; }

  private void undoRecord(Connection c, ExecutionRow record, Database.Context context) throws SQLException {
    EntityTable entity = entityByName(record.entityType()); JsonObject current = snapshot(c, entity.entityType(), entity.table(), context.workspaceId(), record.entityId(), true);
    if (record.versionAfter() != null && current.get("version").getAsInt() != record.versionAfter()) throw new IllegalStateException("undo_version_conflict:" + record.entityId());
    if (record.beforeSnapshot() == null) {
      try (PreparedStatement p = c.prepareStatement("UPDATE " + entity.table() + " SET deleted_at=NOW(),purge_after=DATE_ADD(NOW(),INTERVAL 30 DAY),version=version+1 WHERE id=? AND " + entity.workspaceClause() + " AND version=?")) {
        p.setBytes(1, Database.uuidBytes(record.entityId())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setInt(3, current.get("version").getAsInt()); requireAffected(p.executeUpdate(), "undo_version_conflict");
      }
      return;
    }
    JsonObject before = JsonParser.parseString(record.beforeSnapshot()).getAsJsonObject(); restoreSnapshot(c, entity, record.entityId(), context.workspaceId(), current.get("version").getAsInt(), before);
  }

  private void restoreSnapshot(Connection c, EntityTable entity, UUID id, UUID workspaceId, int currentVersion, JsonObject before) throws SQLException {
    String sql;
    List<Object> values = new ArrayList<>();
    switch (entity.entityType()) {
      case "plan" -> { sql = "UPDATE plans SET title=?,description=?,color=?,status=?,due_date=?,deleted_at=?,purge_after=NULL,version=version+1 WHERE id=? AND workspace_id=? AND version=?"; add(values, before, "title", "description", "color", "status"); values.add(sqlDate(before, "dueDate")); values.add(sqlTime(before, "deletedAt")); }
      case "plan_stage" -> { sql = "UPDATE plan_stages s JOIN plans p ON p.id=s.plan_id SET s.title=?,s.description=?,s.status=?,s.due_date=?,s.sort_order=?,s.deleted_at=?,s.purge_after=NULL,s.version=s.version+1 WHERE s.id=? AND p.workspace_id=? AND s.version=?"; add(values, before, "title", "description", "status"); values.add(sqlDate(before, "dueDate")); values.add(integer(before, "sortOrder", 0)); values.add(sqlTime(before, "deletedAt")); }
      case "plan_task" -> { sql = "UPDATE plan_tasks t JOIN plans p ON p.id=t.plan_id SET t.title=?,t.description=?,t.status=?,t.priority=?,t.estimated_minutes=?,t.actual_minutes=?,t.due_at=?,t.completed_at=?,t.blocked_reason=?,t.sort_order=?,t.deleted_at=?,t.purge_after=NULL,t.version=t.version+1 WHERE t.id=? AND p.workspace_id=? AND t.version=?"; add(values, before, "title", "description", "status", "priority"); values.add(optionalInteger(before, "estimatedMinutes")); values.add(optionalInteger(before, "actualMinutes")); values.add(sqlTime(before, "dueAt")); values.add(sqlTime(before, "completedAt")); values.add(nullable(before, "reason")); values.add(integer(before, "sortOrder", 0)); values.add(sqlTime(before, "deletedAt")); }
      case "todo" -> { sql = "UPDATE todos SET title=?,description=?,status=?,priority=?,due_at=?,completed_at=?,deleted_at=?,purge_after=NULL,version=version+1 WHERE id=? AND workspace_id=? AND version=?"; add(values, before, "title", "description", "status", "priority"); values.add(sqlTime(before, "dueAt")); values.add(sqlTime(before, "completedAt")); values.add(sqlTime(before, "deletedAt")); }
      case "schedule" -> { sql = "UPDATE schedule_items SET title=?,description=?,status=?,start_at=?,duration_minutes=?,plan_id=?,stage_id=?,task_id=?,completed_at=?,deleted_at=?,purge_after=NULL,version=version+1 WHERE id=? AND workspace_id=? AND version=?"; add(values, before, "title", "description", "status"); values.add(sqlTime(before, "startAt")); values.add(integer(before, "durationMinutes", 30)); values.add(uuidBytes(before, "planId")); values.add(uuidBytes(before, "stageId")); values.add(uuidBytes(before, "taskId")); values.add(sqlTime(before, "completedAt")); values.add(sqlTime(before, "deletedAt")); }
      default -> throw new IllegalArgumentException("unsupported_undo_entity");
    }
    try (PreparedStatement p = c.prepareStatement(sql)) { int index = 1; for (Object value : values) p.setObject(index++, value); p.setBytes(index++, Database.uuidBytes(id)); p.setBytes(index++, Database.uuidBytes(workspaceId)); p.setInt(index, currentVersion); requireAffected(p.executeUpdate(), "undo_version_conflict"); }
    UUID planId = planIdFromSnapshot(before); if (planId != null) plans.recomputeProgress(c, planId);
  }

  private void saveDraft(UUID id, UUID changeSetId, Database.Context context, UUID conversationId, String channel, String request, String reply, JsonArray actions) throws SQLException {
    try (Connection c = database.connection()) {
      c.setAutoCommit(false);
      try {
        try (PreparedStatement old = c.prepareStatement("UPDATE ai_action_drafts SET status='superseded',superseded_at=NOW() WHERE conversation_id=? AND workspace_id=? AND user_id=? AND status='pending'")) {
          old.setBytes(1, Database.uuidBytes(conversationId)); old.setBytes(2, Database.uuidBytes(context.workspaceId())); old.setBytes(3, Database.uuidBytes(context.userId())); old.executeUpdate();
        }
        try (PreparedStatement p = c.prepareStatement("INSERT INTO ai_action_drafts (id,workspace_id,user_id,conversation_id,change_set_id,source_channel,request_text,reply,actions,expires_at) VALUES (?,?,?,?,?,?,?,?,?,DATE_ADD(NOW(),INTERVAL 24 HOUR))")) {
          p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setBytes(4, Database.uuidBytes(conversationId)); p.setBytes(5, Database.uuidBytes(changeSetId)); p.setString(6, channel); p.setString(7, request); p.setString(8, reply); p.setString(9, gson.toJson(actions)); p.executeUpdate();
        }
        c.commit();
      } catch (Exception error) { c.rollback(); throw error; }
      finally { c.setAutoCommit(true); }
    }
  }

  private JsonObject draft(UUID id, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT change_set_id,reply,actions,status,expires_at,undone_at FROM ai_action_drafts WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("草案不存在");
        JsonObject result = new JsonObject(); result.addProperty("id", id.toString()); result.addProperty("code", shortCode(id)); result.addProperty("changeSetId", Database.bytesUuid(rs.getBytes("change_set_id")).toString()); result.addProperty("reply", rs.getString("reply")); result.addProperty("status", rs.getString("status")); result.addProperty("expiresAt", rs.getTimestamp("expires_at").toLocalDateTime().toString()); result.addProperty("undone", rs.getTimestamp("undone_at") != null); result.add("actions", JsonParser.parseString(rs.getString("actions")).getAsJsonArray()); return result;
      }
    }
  }

  private DraftRow lockedDraft(Connection c, UUID id, Database.Context context) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("SELECT conversation_id,status,expires_at,actions,change_set_id,source_channel FROM ai_action_drafts WHERE id=? AND workspace_id=? AND user_id=? FOR UPDATE")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("草案不存在"); return new DraftRow(Database.bytesUuid(rs.getBytes(1)), rs.getString(2), rs.getTimestamp(3).toLocalDateTime(), rs.getString(4), Database.bytesUuid(rs.getBytes(5)), rs.getString(6)); }
    }
  }

  private UUID conversationIdForDraft(UUID id, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT conversation_id FROM ai_action_drafts WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next() || rs.getBytes(1) == null) throw new IllegalArgumentException("草案不存在");
        return Database.bytesUuid(rs.getBytes(1));
      }
    }
  }

  private UUID conversation(JsonObject input, Database.Context context, String channel) throws SQLException {
    UUID id = null;
    if (input.has("conversationId") && !input.get("conversationId").isJsonNull()) {
      id = UUID.fromString(input.get("conversationId").getAsString()); requireConversationOwner(id, context);
    } else if (!input.has("newConversation") || !input.get("newConversation").getAsBoolean()) {
      id = recentConversation(context, channel);
    }
    if (id == null) {
      id = UUID.randomUUID();
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO ai_conversations (id,workspace_id,user_id,title,source_channel) VALUES (?,?,?,?,?)")) {
        p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, "新对话"); p.setString(5, channel); p.executeUpdate();
      }
    }
    activateConversation(id, context, channel);
    return id;
  }

  private void activateConversation(UUID id, Database.Context context, String channel) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO ai_channel_sessions (workspace_id,user_id,channel,conversation_id) VALUES (?,?,?,?) ON DUPLICATE KEY UPDATE conversation_id=VALUES(conversation_id)")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, channel); p.setBytes(4, Database.uuidBytes(id)); p.executeUpdate();
    }
  }

  private UUID recentConversation(Database.Context context, String channel) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT s.conversation_id FROM ai_channel_sessions s JOIN ai_conversations a ON a.id=s.conversation_id WHERE s.workspace_id=? AND s.user_id=? AND s.channel=? AND a.workspace_id=? AND a.user_id=?")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, channel); p.setBytes(4, Database.uuidBytes(context.workspaceId())); p.setBytes(5, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { if (rs.next()) return Database.bytesUuid(rs.getBytes(1)); }
    }
    UUID fallback = null;
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id FROM ai_conversations WHERE workspace_id=? AND user_id=? AND source_channel=? "
            + "ORDER BY updated_at DESC,id DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, channel);
      try (ResultSet rs = p.executeQuery()) { if (rs.next()) fallback = Database.bytesUuid(rs.getBytes(1)); }
    }
    if (fallback != null) activateConversation(fallback, context, channel);
    return fallback;
  }

  private void requireConversationOwner(UUID id, Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT 1 FROM ai_conversations WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("conversation_not_found"); }
    }
  }

  private JsonArray historyForModel(UUID conversationId, Database.Context owner) throws SQLException {
    JsonArray result = new JsonArray();
    String summary = memory.conversationSummary(conversationId, owner);
    if (!summary.isBlank()) result.add(message("system", "此前会话摘要：\n" + summary));
    JsonArray payload = historyPayload(conversationId, null, summary.isBlank() ? 40 : 12);
    for (JsonElement element : payload) {
      JsonObject row = element.getAsJsonObject();
      result.add(message(row.get("role").getAsString(), row.get("content").getAsString()));
    }
    return result;
  }

  private JsonArray historyPayload(UUID conversationId, Database.Context owner) throws SQLException {
    return historyPayload(conversationId, owner, 40);
  }

  private JsonArray historyPayload(UUID conversationId, Database.Context owner, int limit) throws SQLException {
    if (owner != null) requireConversationOwner(conversationId, owner); List<JsonObject> rows = new ArrayList<>();
    String sql = "SELECT role,content,created_at FROM ai_messages WHERE conversation_id=? ORDER BY created_at DESC,id DESC"
        + (limit > 0 ? " LIMIT ?" : "");
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(conversationId)); if (limit > 0) p.setInt(2, limit);
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) { JsonObject row = message(rs.getString(1), rs.getString(2)); row.addProperty("createdAt", rs.getTimestamp(3).toLocalDateTime().toString()); rows.add(row); } }
    }
    JsonArray result = new JsonArray(); for (int i = rows.size() - 1; i >= 0; i--) result.add(rows.get(i)); return result;
  }

  private JsonArray recentExecution(Database.Context context) throws SQLException {
    JsonArray rows = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT entity_type,action_type,reason,actual_minutes,occurred_at FROM execution_records WHERE workspace_id=? AND user_id=? AND occurred_at>=DATE_SUB(NOW(),INTERVAL 7 DAY) AND undone_at IS NULL ORDER BY occurred_at DESC LIMIT 80")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) { while (rs.next()) { JsonObject row = new JsonObject(); row.addProperty("entityType", rs.getString(1)); row.addProperty("action", rs.getString(2)); row.addProperty("reason", rs.getString(3)); row.addProperty("actualMinutes", integer(rs.getObject(4))); row.addProperty("occurredAt", rs.getTimestamp(5).toLocalDateTime().toString()); rows.add(row); } }
    }
    return rows;
  }

  private void record(Connection c, Database.Context context, UUID draftId, UUID changeSetId, String entityType, UUID entityId, String action, JsonObject before, JsonObject after, String reason, String source, Integer minutes, Integer versionAfter) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("INSERT INTO execution_records (id,workspace_id,user_id,draft_id,change_set_id,entity_type,entity_id,action_type,before_snapshot,after_snapshot,reason,source_channel,note,actual_minutes,version_after,occurred_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,NOW())")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setBytes(4, draftId == null ? null : Database.uuidBytes(draftId)); p.setBytes(5, changeSetId == null ? null : Database.uuidBytes(changeSetId)); p.setString(6, entityType); p.setBytes(7, Database.uuidBytes(entityId)); p.setString(8, action); p.setString(9, before == null ? null : gson.toJson(before)); p.setString(10, after == null ? null : gson.toJson(after)); p.setString(11, reason); p.setString(12, source); p.setString(13, reason); if (minutes == null) p.setObject(14, null); else p.setInt(14, minutes); if (versionAfter == null) p.setObject(15, null); else p.setInt(15, versionAfter); p.executeUpdate();
    }
  }

  private void saveReviewFacts(Database.Context context, JsonObject facts) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO review_entries (id,workspace_id,user_id,review_date,facts) VALUES (?,?,?,?,?) ON DUPLICATE KEY UPDATE facts=VALUES(facts)")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setDate(4, java.sql.Date.valueOf(LocalDate.now())); p.setString(5, gson.toJson(facts)); p.executeUpdate();
    }
  }

  private int count(String sql, Database.Context context) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) { p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); } } }
  private int countRecords(Database.Context context, String pattern) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*) FROM execution_records WHERE workspace_id=? AND user_id=? AND DATE(occurred_at)=CURDATE() AND action_type LIKE ? AND undone_at IS NULL")) { p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, pattern); try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); } } }
  private int sumMinutes(Database.Context context) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT COALESCE(SUM(actual_minutes),0) FROM execution_records WHERE workspace_id=? AND user_id=? AND DATE(occurred_at)=CURDATE() AND undone_at IS NULL")) { p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getInt(1); } } }
  private double estimationError(Database.Context context) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT COALESCE(AVG(ABS(t.actual_minutes-t.estimated_minutes)),0) FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE p.workspace_id=? AND t.completed_at>=DATE_SUB(NOW(),INTERVAL 7 DAY) AND t.actual_minutes IS NOT NULL AND t.estimated_minutes IS NOT NULL")) { p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { rs.next(); return Math.round(rs.getDouble(1) * 10) / 10.0; } } }

  private void validateFields(String type, JsonObject f) {
    List<String> allowed;
    if (type.endsWith("_plan")) allowed = List.of("title", "description", "color", "status", "dueDate", "stages", "reason", "expectedVersion");
    else if (type.endsWith("_stage")) allowed = List.of("planId", "title", "description", "status", "dueDate", "sortOrder", "tasks", "reason", "expectedVersion");
    else if (type.endsWith("_task")) allowed = List.of("planId", "stageId", "title", "description", "status", "priority", "estimatedMinutes", "actualMinutes", "dueAt", "sortOrder", "reason", "schedules", "expectedVersion", "actionType");
    else if (type.endsWith("_todo")) allowed = List.of("title", "description", "status", "priority", "dueAt", "actualMinutes", "reason", "expectedVersion");
    else if (type.endsWith("_schedule")) allowed = List.of("title", "description", "status", "startAt", "durationMinutes", "planId", "stageId", "taskId", "actualMinutes", "reason", "expectedVersion", "actionType");
    else if ("batch_reschedule".equals(type)) allowed = List.of("items", "reason");
    else allowed = List.of("timezone", "availability", "maxSessionMinutes", "bufferMinutes");
    requireOnly(f, allowed);
    if ("create_stage".equals(type)) UUID.fromString(required(f, "planId"));
    if ("create_task".equals(type)) { UUID.fromString(required(f, "planId")); UUID.fromString(required(f, "stageId")); }
    if ("create_schedule".equals(type)) { required(f, "startAt"); if (integer(f, "durationMinutes", 0) < 1) throw new IllegalArgumentException("durationMinutes_required"); }
    if ("delay_task".equals(type) && string(f, "dueAt", "").isBlank()) throw new IllegalArgumentException("延期任务缺少新截止时间");
    if ("delay_todo".equals(type) && string(f, "dueAt", "").isBlank()) throw new IllegalArgumentException("延期待办缺少新截止时间");
    if ("delay_schedule".equals(type) && string(f, "startAt", "").isBlank()) throw new IllegalArgumentException("延期日程缺少新开始时间");
    if ("block_task".equals(type) && string(f, "reason", "").isBlank()) throw new IllegalArgumentException("阻塞任务必须填写原因");
    if ("batch_reschedule".equals(type) && array(f, "items").isEmpty()) throw new IllegalArgumentException("批量重排缺少具体项目");
    String status = nullable(f, "status");
    if (type.endsWith("_plan") && status != null && !List.of("active", "paused", "completed").contains(status)) throw new IllegalArgumentException("invalid_plan_status");
    if (type.endsWith("_stage") && status != null && !List.of("pending", "in_progress", "done", "blocked", "cancelled").contains(status)) throw new IllegalArgumentException("invalid_stage_status");
    if (type.endsWith("_todo") && status != null && !List.of("pending", "done", "delayed", "cancelled").contains(status)) throw new IllegalArgumentException("invalid_todo_status");
    if (type.endsWith("_schedule") && status != null && !List.of("pending", "done", "delayed", "cancelled").contains(status)) throw new IllegalArgumentException("invalid_schedule_status");
    if (f.has("priority") && !List.of("high", "medium", "low").contains(f.get("priority").getAsString())) throw new IllegalArgumentException("invalid_priority");
    if (f.has("dueDate") && !f.get("dueDate").isJsonNull()) LocalDate.parse(f.get("dueDate").getAsString());
    if (f.has("dueAt") && !f.get("dueAt").isJsonNull()) LocalDateTime.parse(f.get("dueAt").getAsString());
    if (f.has("startAt") && !f.get("startAt").isJsonNull()) LocalDateTime.parse(f.get("startAt").getAsString());
  }

  private void requireOnly(JsonObject value, List<String> allowed) {
    for (String key : value.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("不支持的字段：" + key);
  }

  private EntityTable entityTable(String actionType) {
    if (actionType.endsWith("_plan")) return new EntityTable("plan", "plans", "workspace_id=?");
    if (actionType.endsWith("_stage")) return new EntityTable("plan_stage", "plan_stages", "plan_id IN (SELECT id FROM plans WHERE workspace_id=?)");
    if (actionType.endsWith("_task")) return new EntityTable("plan_task", "plan_tasks", "plan_id IN (SELECT id FROM plans WHERE workspace_id=?)");
    if (actionType.endsWith("_todo")) return new EntityTable("todo", "todos", "workspace_id=?");
    if (actionType.endsWith("_schedule")) return new EntityTable("schedule", "schedule_items", "workspace_id=?");
    throw new IllegalArgumentException("不支持的目标类型：" + actionType);
  }
  private EntityTable entityByName(String type) { return switch (type) { case "plan" -> entityTable("update_plan"); case "plan_stage" -> entityTable("update_stage"); case "plan_task" -> entityTable("update_task"); case "todo" -> entityTable("update_todo"); case "schedule" -> entityTable("update_schedule"); default -> throw new IllegalArgumentException("不支持撤销的实体：" + type); }; }

  private boolean needsTarget(String type) { return !type.startsWith("create_") && !"batch_reschedule".equals(type) && !"update_preference".equals(type); }
  private boolean containsAction(JsonArray actions, String type) { for (JsonElement item : actions) if (item.isJsonObject() && type.equals(string(item.getAsJsonObject(), "type", ""))) return true; return false; }
  private boolean requestsDraft(String text) {
    String value = text.replaceAll("\\s", "");
    if (value.contains("不要创建") || value.contains("不要生成") || value.contains("不要安排")) return false;
    return value.contains("创建") || value.contains("布置") || value.contains("安排")
        || value.contains("生成任务") || value.contains("制定计划") || value.contains("加入待办")
        || value.contains("排进日程") || value.contains("调整计划");
  }
  private boolean requestsScheduling(String text) {
    String value = text.replaceAll("\\s", "");
    return value.contains("排期") || value.contains("日程") || value.contains("时间块")
        || value.contains("安排时间") || value.contains("几点开始");
  }
  private void discardUnrequestedSchedules(JsonArray actions) {
    for (int index = actions.size() - 1; index >= 0; index--) {
      JsonObject action = actions.get(index).getAsJsonObject();
      String type = string(action, "type", "");
      if (type.contains("schedule") || "batch_reschedule".equals(type)) {
        actions.remove(index);
      } else {
        removeNestedSchedules(fields(action));
      }
    }
  }
  private void removeNestedSchedules(JsonElement value) {
    if (value.isJsonArray()) {
      for (JsonElement item : value.getAsJsonArray()) removeNestedSchedules(item);
      return;
    }
    if (!value.isJsonObject()) return;
    JsonObject object = value.getAsJsonObject();
    object.remove("schedules");
    for (JsonElement child : object.asMap().values()) removeNestedSchedules(child);
  }
  private void normalizeModelFields(JsonElement value) {
    if (value.isJsonArray()) {
      for (JsonElement item : value.getAsJsonArray()) normalizeModelFields(item);
      return;
    }
    if (!value.isJsonObject()) return;
    JsonObject object = value.getAsJsonObject();
    object.remove("version");
    for (String key : List.copyOf(object.keySet())) {
      JsonElement child = object.get(key);
      if ("dueDate".equals(key) && child.isJsonPrimitive()) {
        String date = child.getAsString();
        if (date.length() > 10) object.addProperty(key, date.substring(0, 10));
      } else if (("dueAt".equals(key) || "startAt".equals(key)) && child.isJsonPrimitive()) {
        String dateTime = child.getAsString();
        if (dateTime.length() == 10) object.addProperty(key, dateTime + "T23:59:59");
      }
      normalizeModelFields(object.get(key));
    }
  }
  private boolean containsScheduling(JsonArray actions) { for (JsonElement item : actions) { if (!item.isJsonObject()) continue; JsonObject action = item.getAsJsonObject(); String type = string(action, "type", ""); if (type.contains("schedule") || "batch_reschedule".equals(type)) return true; if ("create_plan".equals(type) && gson.toJson(fields(action)).contains("startAt")) return true; } return false; }
  private JsonObject fields(JsonObject action) { JsonObject fields = action.has("fields") && action.get("fields").isJsonObject() ? action.getAsJsonObject("fields") : new JsonObject(); action.add("fields", fields); return fields; }
  private JsonArray array(JsonObject object, String key) { return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : new JsonArray(); }
  private JsonArray changes(JsonObject before, JsonObject fields) { JsonArray rows = new JsonArray(); for (String key : fields.keySet()) { if ("expectedVersion".equals(key) || "reason".equals(key)) continue; JsonObject row = new JsonObject(); row.addProperty("field", key); row.add("before", before != null && before.has(key) ? before.get(key).deepCopy() : JsonNull.INSTANCE); row.add("after", fields.get(key).deepCopy()); rows.add(row); } return rows; }
  private JsonObject item(String type, String id, JsonObject action) { JsonObject row = new JsonObject(); row.addProperty("type", type); row.addProperty("entityId", id); row.addProperty("summary", string(action, "summary", type)); return row; }
  private JsonObject simpleItem(String type, UUID id, String summary) { JsonObject row = new JsonObject(); row.addProperty("type", type); row.addProperty("entityId", id.toString()); row.addProperty("summary", summary); return row; }
  private JsonObject message(String role, String content) { JsonObject row = new JsonObject(); row.addProperty("role", role); row.addProperty("content", content); return row; }
  private void saveMessage(UUID conversationId, String role, String content, JsonArray actions) throws SQLException {
    try (Connection c = database.connection()) {
      try (PreparedStatement p = c.prepareStatement(
          "INSERT INTO ai_messages (id,conversation_id,role,content,proposed_changes) VALUES (?,?,?,?,?)")) {
        p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(conversationId));
        p.setString(3, role); p.setString(4, content);
        p.setString(5, actions == null ? null : gson.toJson(actions)); p.executeUpdate();
      }
      String title = content.replaceAll("\\s+", " ").trim();
      if (title.length() > 40) title = title.substring(0, 40) + "...";
      try (PreparedStatement p = c.prepareStatement(
          "UPDATE ai_conversations SET updated_at=NOW(),title=CASE WHEN ?='user' AND title IN ('新对话','AI 规划') "
              + "THEN ? ELSE title END WHERE id=?")) {
        p.setString(1, role); p.setString(2, title.isBlank() ? "新对话" : title);
        p.setBytes(3, Database.uuidBytes(conversationId)); p.executeUpdate();
      }
    }
  }
  private void updateDraftStatus(Connection c, UUID id, String status) throws SQLException { try (PreparedStatement p = c.prepareStatement("UPDATE ai_action_drafts SET status=?,confirmed_at=CASE WHEN ?='confirmed' THEN NOW() ELSE confirmed_at END WHERE id=?")) { p.setString(1, status); p.setString(2, status); p.setBytes(3, Database.uuidBytes(id)); p.executeUpdate(); } }
  private UUID draftId(String reference, Database.Context context) throws SQLException { try { return UUID.fromString(reference); } catch (IllegalArgumentException ignored) { } try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT id FROM ai_action_drafts WHERE workspace_id=? AND user_id=? AND LOWER(HEX(id)) LIKE ? ORDER BY created_at DESC LIMIT 2")) { p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setBytes(2, Database.uuidBytes(context.userId())); p.setString(3, reference.toLowerCase().replace("-", "") + "%"); try (ResultSet rs = p.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("草案编号不存在"); UUID id = Database.bytesUuid(rs.getBytes(1)); if (rs.next()) throw new IllegalArgumentException("草案编号不唯一，请使用完整编号"); return id; } } }
  private void requireAffected(int affected, String message) { if (affected == 0) throw new IllegalStateException(message); }
  private String required(JsonObject value, String name) { String result = string(value, name, "").trim(); if (result.isBlank()) throw new IllegalArgumentException(name + "_required"); return result; }
  private String string(JsonObject value, String name, String fallback) { JsonElement item = value.get(name); return item == null || item.isJsonNull() ? fallback : item.getAsString(); }
  private String nullable(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : null; }
  private Integer optionalInteger(JsonObject value, String name) { return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsInt() : null; }
  private int integer(JsonObject value, String name, int fallback) { Integer result = optionalInteger(value, name); return result == null ? fallback : result; }
  private static Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
  private String priority(JsonObject value) { String result = string(value, "priority", "medium"); if (!List.of("high", "medium", "low").contains(result)) throw new IllegalArgumentException("invalid_priority"); return result; }
  private java.sql.Date date(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : java.sql.Date.valueOf(text); }
  private Timestamp timestamp(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : Timestamp.valueOf(LocalDateTime.parse(text)); }
  private java.sql.Date sqlDate(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : java.sql.Date.valueOf(text); }
  private Timestamp sqlTime(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : Timestamp.valueOf(LocalDateTime.parse(text)); }
  private byte[] uuidBytes(JsonObject value, String name) { String text = nullable(value, name); return text == null || text.isBlank() ? null : Database.uuidBytes(UUID.fromString(text)); }
  private void add(List<Object> values, JsonObject source, String... names) { for (String name : names) values.add(nullable(source, name)); }
  private void addUuid(JsonObject row, String name, byte[] value) { row.addProperty(name, value == null ? null : Database.bytesUuid(value).toString()); }
  private void addProgress(JsonObject row, ResultSet rs) throws SQLException { row.addProperty("progress", rs.getDouble("progress")); row.addProperty("taskProgress", rs.getDouble("task_progress")); row.addProperty("effortProgress", rs.getDouble("effort_progress")); }
  private String dateText(ResultSet rs, String name) throws SQLException { return rs.getDate(name) == null ? null : rs.getDate(name).toString(); }
  private String timeText(ResultSet rs, String name) throws SQLException { return rs.getTimestamp(name) == null ? null : rs.getTimestamp(name).toLocalDateTime().toString(); }
  private UUID planIdFromSnapshot(JsonObject snapshot) { return snapshot.has("planId") && !snapshot.get("planId").isJsonNull() ? UUID.fromString(snapshot.get("planId").getAsString()) : null; }
  private String shortCode(UUID id) { return id.toString().replace("-", "").substring(0, 8); }

  private record DraftRow(UUID conversationId, String status, LocalDateTime expiresAt, String actions, UUID changeSetId, String sourceChannel) {}
  private record ExecutionRow(UUID id, String entityType, UUID entityId, String beforeSnapshot, Integer versionAfter) {}
  private record EntityTable(String entityType, String table, String workspaceClause) {}
}
