package com.changlu.planner.agent.core;

import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.agent.subagents.document.DocumentSubagent;
import com.changlu.planner.agent.subagents.memory.MemorySubagent;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主 Agent 运行循环。专业能力由 Registry 提供，运行时只负责调度、状态和恢复。 */
public final class AgentRuntime implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);
  private static final int MAX_ITERATIONS = 6;

  private final Database database;
  private final AiCommandService commands;
  private final AgentRouter router;
  private final ToolRegistry tools;
  private final com.changlu.planner.agent.core.registry.SubagentRegistry subagents;
  private final com.changlu.planner.agent.core.tool.ToolRegistry standardTools;
  private final DocumentSubagent documents;
  private final MemorySubagent memory;
  private final ToolExecutor executor = new ToolExecutor();
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final ExecutorService subagentExecutions = Executors.newVirtualThreadPerTaskExecutor();
  private final Gson gson = new Gson();
  private final com.changlu.planner.agent.core.runtime.TraceRecorder traces =
      new com.changlu.planner.agent.core.runtime.TraceRecorder(LOG);
  private final com.changlu.planner.agent.core.runtime.JsonSchemaValidator schemaValidator =
      new com.changlu.planner.agent.core.runtime.JsonSchemaValidator();

  public AgentRuntime(Database database, AiCommandService commands, AgentRouter router,
                      ToolRegistry tools,
                      com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                      com.changlu.planner.agent.core.tool.ToolRegistry standardTools,
                      DocumentSubagent documents, MemorySubagent memory) {
    this.database = database;
    this.commands = commands;
    this.router = router;
    this.tools = tools;
    this.subagents = subagents;
    this.standardTools = standardTools;
    this.documents = documents;
    this.memory = memory;
    this.standardTools.setObserver(new StandardToolObserver());
  }

  public JsonObject start(JsonObject input, Database.Context identity, String channel) throws Exception {
    String message = required(input, "message");
    UUID conversationId = commands.ensureConversation(input, identity, channel);
    UUID runId = UUID.randomUUID();
    createRun(runId, identity, conversationId, channel, message);
    JsonObject request = input.deepCopy();
    request.addProperty("conversationId", conversationId.toString());
    return execute(runId, request, identity, channel, 0);
  }

  public JsonObject startAsync(JsonObject input, Database.Context identity, String channel) throws Exception {
    String message = required(input, "message");
    UUID conversationId = commands.ensureConversation(input, identity, channel);
    UUID runId = UUID.randomUUID();
    createRun(runId, identity, conversationId, channel, message);
    JsonObject request = input.deepCopy();
    request.addProperty("conversationId", conversationId.toString());
    submit(runId, () -> execute(runId, request, identity, channel, 0));
    return accepted(runId, conversationId, 0);
  }

  public JsonObject resume(String reference, JsonObject input, Database.Context identity) throws Exception {
    UUID runId = UUID.fromString(reference);
    RunRow run = run(runId, identity);
    validateResumable(run);
    String message = required(input, "message");
    appendGoal(runId, message);
    JsonObject request = input.deepCopy();
    request.addProperty("conversationId", run.conversationId().toString());
    return execute(runId, request, identity, run.channel(), run.iteration());
  }

  public JsonObject resumeAsync(String reference, JsonObject input, Database.Context identity) throws Exception {
    UUID runId = UUID.fromString(reference);
    RunRow run = run(runId, identity);
    validateResumable(run);
    String message = required(input, "message");
    appendGoal(runId, message);
    markRunning(runId);
    JsonObject request = input.deepCopy();
    request.addProperty("conversationId", run.conversationId().toString());
    submit(runId, () -> execute(runId, request, identity, run.channel(), run.iteration()));
    return accepted(runId, run.conversationId(), run.iteration());
  }

  public JsonObject get(String reference, Database.Context identity) throws Exception {
    UUID runId = UUID.fromString(reference);
    RunRow row = run(runId, identity);
    JsonObject result = row.result() == null ? new JsonObject()
        : JsonParser.parseString(row.result()).getAsJsonObject();
    result.addProperty("runId", runId.toString());
    result.addProperty("status", row.status());
    result.addProperty("iteration", row.iteration());
    result.addProperty("conversationId", row.conversationId().toString());
    result.addProperty("goal", row.goal());
    result.add("toolCalls", toolCalls(runId));
    if (row.error() != null) result.addProperty("lastError", row.error());
    return result;
  }

  public JsonObject confirm(String draftReference, Database.Context identity) throws Exception {
    JsonObject result = commands.confirm(draftReference, identity);
    finishDraft(UUID.fromString(result.get("id").getAsString()), identity, "COMPLETED", result);
    workflow("确认执行", "用户操作", "计划变更", "用户确认执行待处理草案");
    workflow("变更完成", "Agent消息", "计划变更", "已执行 " + result.getAsJsonArray("executed").size() + " 项操作");
    return result;
  }

  public JsonObject cancel(String draftReference, Database.Context identity) throws Exception {
    JsonObject result = commands.cancel(draftReference, identity);
    finishDraft(UUID.fromString(result.get("id").getAsString()), identity, "CANCELLED", result);
    workflow("取消执行", "用户操作", "计划变更", "用户取消待处理草案");
    return result;
  }

  public JsonObject session(Database.Context identity, String channel) throws Exception {
    JsonObject result = commands.session(identity, channel);
    if (result.has("conversationId")) {
      addRunState(result, UUID.fromString(result.get("conversationId").getAsString()), identity);
    }
    return result;
  }

  public JsonObject createConversation(Database.Context identity, String channel) throws Exception {
    return commands.createConversation(identity, channel);
  }

  public JsonObject conversation(String reference, Database.Context identity, String channel) throws Exception {
    JsonObject result = commands.conversationDetail(reference, identity, channel);
    addRunState(result, UUID.fromString(reference), identity);
    return result;
  }

  private void addRunState(JsonObject result, UUID conversationId, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id,status FROM agent_runs WHERE workspace_id=? AND user_id=? AND conversation_id=? "
            + "ORDER BY updated_at DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(2, Database.uuidBytes(identity.userId()));
      p.setBytes(3, Database.uuidBytes(conversationId));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          result.addProperty("runId", Database.bytesUuid(rs.getBytes("id")).toString());
          result.addProperty("runStatus", rs.getString("status"));
        }
      }
    }
  }

  private JsonObject execute(UUID runId, JsonObject input, Database.Context identity, String channel,
                             int currentIteration) throws Exception {
    if (currentIteration >= MAX_ITERATIONS) {
      failRun(runId, "达到最大循环次数 " + MAX_ITERATIONS);
      return get(runId.toString(), identity);
    }
    int iteration = currentIteration + 1;
    updateRunning(runId, iteration);
    String message = required(input, "message");
    workflow("收到消息", "用户消息", "待判断", message);
    AgentRouter.Decision decision = router.route(message, documents.hasAttachments(input), tools, subagents);
    String route = routeName(decision.executorName());
    workflow("路由完成", "路由决策", route, "进入" + route + " Agent");
    String toolCallId = runId + ":" + iteration;
    JsonObject arguments = new JsonObject();
    arguments.addProperty("message", message);
    startCall(runId, toolCallId, decision.executorType(), decision.executorName(), arguments,
        "tool".equals(decision.executorType()) && tools.require(decision.executorName()).requiresConfirmation());
    try {
      JsonObject result;
      if ("subagent".equals(decision.executorType())) {
        com.changlu.planner.agent.core.contract.Subagent subagent = subagents.require(decision.executorName());
        UUID conversationId = UUID.fromString(input.get("conversationId").getAsString());
        String traceId = runId.toString();
        var context = new com.changlu.planner.agent.core.contract.AgentContext(runId, conversationId, traceId,
            identity, channel, Set.of("travel:read", "planning:write"),
            Instant.now().plus(subagent.definition().timeout()), input.deepCopy());
        var request = new com.changlu.planner.agent.core.contract.SubagentRequest(message,
            object(input, "arguments"), documentIds(input));
        JsonObject schemaInput = new JsonObject();
        schemaInput.addProperty("message", message);
        schemaInput.add("arguments", request.arguments().deepCopy());
        JsonArray schemaDocumentIds = new JsonArray();
        request.documentIds().forEach(schemaDocumentIds::add);
        schemaInput.add("documentIds", schemaDocumentIds);
        schemaValidator.validate(schemaInput, subagent.definition().inputSchema());
        long startedAt = System.nanoTime();
        com.changlu.planner.agent.core.contract.AgentResult agentResult =
            executeSubagent(subagent, request, context);
        traces.event(traceId, runId.toString(), decision.executorName(), "completed",
            (System.nanoTime() - startedAt) / 1_000_000, agentResult.status().jsonValue());
        result = agentResult.toJson();
        commands.saveExchange(UUID.fromString(input.get("conversationId").getAsString()), message,
            string(result, "reply", "已完成。"));
      } else {
        tools.require(decision.executorName());
        JsonObject commandInput = input.deepCopy();
        String documentContext = documents.planningContext(commandInput, identity, message);
        if (!documentContext.isBlank()) commandInput.addProperty("knowledgeContext", documentContext);
        result = executor.execute(() -> commands.command(commandInput, identity, channel));
      }
      memory.afterExchange(UUID.fromString(input.get("conversationId").getAsString()), message,
          string(result, "reply", ""), identity);
      finishCall(toolCallId, "COMPLETED", result, null);
      recordProposedTools(runId, iteration, result);
      JsonObject response = finishRun(runId, iteration, input, decision, result, identity);
      workflow("返回消息", "Agent消息", route, string(response, "reply", "已完成处理"));
      workflow("工作流结束", "系统状态", route, statusName(string(response, "status", "COMPLETED")));
      return response;
    } catch (Exception error) {
      finishCall(toolCallId, "FAILED", null, error.getMessage());
      failRun(runId, error.getMessage());
      workflow("执行失败", "错误消息", route, error.getMessage());
      throw error;
    }
  }

  private void workflow(String step, String type, String route, String content) {
    LOG.info("[工作流] {}｜{}｜{}｜{}", step, type, route, compact(content));
  }

  private String routeName(String executorName) {
    if ("planning.assistant".equals(executorName)) return "计划管理";
    return subagents.contains(executorName) ? subagents.require(executorName).definition().description() : executorName;
  }

  private String statusName(String status) {
    return switch (status) {
      case "WAITING_CONFIRMATION" -> "等待用户确认";
      case "WAITING_USER" -> "等待用户补充";
      case "FAILED" -> "执行失败";
      case "CANCELLED" -> "已取消";
      default -> "已完成";
    };
  }

  private String compact(String value) {
    if (value == null || value.isBlank()) return "无内容";
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
  }

  private JsonObject finishRun(UUID runId, int iteration, JsonObject input, AgentRouter.Decision decision,
                               JsonObject toolResult, Database.Context identity) throws SQLException {
    JsonObject response = toolResult.deepCopy();
    if (toolResult.has("schemaVersion")) response.add("agentResult", toolResult.deepCopy());
    if (!response.has("questions")) response.add("questions", new JsonArray());
    if (!response.has("actions")) response.add("actions", new JsonArray());
    response.addProperty("runId", runId.toString());
    response.addProperty("iteration", iteration);
    response.addProperty("executorType", decision.executorType());
    response.addProperty("executorName", decision.executorName());
    if (!response.has("conversationId")) response.add("conversationId", input.get("conversationId").deepCopy());
    JsonObject draft = response.has("draft") && response.get("draft").isJsonObject()
        ? response.getAsJsonObject("draft") : null;
    JsonArray questions = response.has("questions") && response.get("questions").isJsonArray()
        ? response.getAsJsonArray("questions") : new JsonArray();
    String status = draft != null ? "WAITING_CONFIRMATION" : questions.isEmpty() ? "COMPLETED" : "WAITING_USER";
    response.addProperty("status", status);
    UUID draftId = draft == null ? null : UUID.fromString(draft.get("id").getAsString());
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status=?,summary=?,result=?,pending_draft_id=?,last_error=NULL,"
            + "completed_at=CASE WHEN ?='COMPLETED' THEN NOW() ELSE NULL END WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setString(1, status);
      p.setString(2, string(response, "reply", ""));
      p.setString(3, gson.toJson(response));
      p.setBytes(4, draftId == null ? null : Database.uuidBytes(draftId));
      p.setString(5, status);
      p.setBytes(6, Database.uuidBytes(runId));
      p.setBytes(7, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(8, Database.uuidBytes(identity.userId()));
      p.executeUpdate();
    }
    return response;
  }

  private void recordProposedTools(UUID runId, int iteration, JsonObject result) throws SQLException {
    if (!result.has("actions") || !result.get("actions").isJsonArray()) return;
    JsonArray actions = result.getAsJsonArray("actions");
    for (int index = 0; index < actions.size(); index++) {
      JsonObject action = actions.get(index).getAsJsonObject();
      String name = string(action, "type", "");
      if (!tools.contains(name)) continue;
      String callId = runId + ":" + iteration + ":action:" + index;
      startCall(runId, callId, "tool", name, action, true);
      finishCall(callId, "WAITING_CONFIRMATION", null, null);
    }
  }

  private void createRun(UUID id, Database.Context context, UUID conversationId, String channel, String goal)
      throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO agent_runs (id,workspace_id,user_id,conversation_id,channel,goal,status,iteration) "
            + "VALUES (?,?,?,?,?,?,'RUNNING',0)")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      p.setBytes(4, Database.uuidBytes(conversationId));
      p.setString(5, channel);
      p.setString(6, goal);
      p.executeUpdate();
    }
  }

  private void updateRunning(UUID id, int iteration) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status='RUNNING',iteration=?,last_error=NULL WHERE id=?")) {
      p.setInt(1, iteration); p.setBytes(2, Database.uuidBytes(id)); p.executeUpdate();
    }
  }

  private void markRunning(UUID id) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status='RUNNING',last_error=NULL WHERE id=?")) {
      p.setBytes(1, Database.uuidBytes(id)); p.executeUpdate();
    }
  }

  private void validateResumable(RunRow run) {
    if ("RUNNING".equals(run.status())) throw new IllegalStateException("当前运行仍在处理中");
    if ("WAITING_CONFIRMATION".equals(run.status())) throw new IllegalStateException("当前运行正在等待草案确认");
    if ("COMPLETED".equals(run.status()) || "CANCELLED".equals(run.status())) {
      throw new IllegalStateException("当前运行已经结束");
    }
  }

  private JsonObject accepted(UUID runId, UUID conversationId, int iteration) {
    JsonObject result = new JsonObject();
    result.addProperty("runId", runId.toString());
    result.addProperty("conversationId", conversationId.toString());
    result.addProperty("status", "RUNNING");
    result.addProperty("iteration", iteration);
    return result;
  }

  private void submit(UUID runId, AgentWork work) {
    workers.submit(() -> {
      try {
        work.execute();
      } catch (Exception error) {
        LOG.error("[工作流] 后台执行失败｜错误消息｜其他任务｜{}", compact(error.getMessage()));
      }
    });
  }

  @Override
  public void close() {
    workers.shutdownNow();
    subagentExecutions.shutdownNow();
    standardTools.close();
  }

  private com.changlu.planner.agent.core.contract.AgentResult executeSubagent(
      com.changlu.planner.agent.core.contract.Subagent subagent,
      com.changlu.planner.agent.core.contract.SubagentRequest request,
      com.changlu.planner.agent.core.contract.AgentContext context) throws Exception {
    Future<com.changlu.planner.agent.core.contract.AgentResult> future =
        subagentExecutions.submit(() -> subagent.execute(request, context));
    long timeoutMs = Math.max(1, context.deadline().toEpochMilli() - Instant.now().toEpochMilli());
    try {
      return future.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new IllegalStateException("SUBAGENT_TIMEOUT:" + subagent.definition().name(), error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception exception) throw exception;
      throw new IllegalStateException("subagent_execution_failed", cause);
    } catch (InterruptedException error) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw error;
    }
  }

  private JsonObject object(JsonObject input, String name) {
    return input.has(name) && input.get(name).isJsonObject()
        ? input.getAsJsonObject(name).deepCopy() : new JsonObject();
  }

  private List<String> documentIds(JsonObject input) {
    if (!input.has("documentIds") || !input.get("documentIds").isJsonArray()) return List.of();
    List<String> ids = new ArrayList<>();
    for (JsonElement value : input.getAsJsonArray("documentIds")) {
      if (value.isJsonPrimitive()) ids.add(value.getAsString());
    }
    return List.copyOf(ids);
  }

  private final class StandardToolObserver implements com.changlu.planner.agent.core.tool.ToolRegistry.Observer {
    @Override public com.changlu.planner.agent.core.contract.AgentResult started(
                                  com.changlu.planner.agent.core.tool.ToolCall call,
                                  com.changlu.planner.agent.core.tool.ToolDefinition definition,
                                  com.changlu.planner.agent.core.contract.AgentContext context,
                                  int attempt) throws Exception {
      if (call.idempotencyKey() != null && !call.idempotencyKey().isBlank()) {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "SELECT result,status FROM agent_tool_calls WHERE run_id=? AND tool_call_id=? LIMIT 1")) {
          p.setBytes(1, Database.uuidBytes(context.runId()));
          p.setString(2, call.toolCallId());
          try (ResultSet rs = p.executeQuery()) {
            if (rs.next() && rs.getString("result") != null
                && ("COMPLETED".equals(rs.getString("status"))
                    || "WAITING_CONFIRMATION".equals(rs.getString("status")))) {
              return com.changlu.planner.agent.core.contract.AgentResult.fromJson(
                  JsonParser.parseString(rs.getString("result")).getAsJsonObject());
            }
          }
        }
      }
      startCall(context.runId(), call.toolCallId(), "tool", definition.name(), call.arguments(),
          definition.requiresConfirmation());
      return null;
    }

    @Override public void finished(com.changlu.planner.agent.core.tool.ToolCall call,
                                   com.changlu.planner.agent.core.tool.ToolDefinition definition,
                                   com.changlu.planner.agent.core.contract.AgentContext context,
                                   int attempt,
                                   com.changlu.planner.agent.core.contract.AgentResult result,
                                   Exception error, long durationMs) throws Exception {
      String status = error != null ? "FAILED" : result.requiresConfirmation()
          ? "WAITING_CONFIRMATION" : "COMPLETED";
      finishCall(call.toolCallId(), status, result == null ? null : result.toJson(),
          error == null ? null : error.getMessage());
      traces.event(context.traceId(), context.runId().toString(), definition.name(), status.toLowerCase(),
          durationMs, error == null ? result.message() : error.getMessage());
    }
  }

  private void appendGoal(UUID id, String message) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET goal=CONCAT(goal,'\\n',?) WHERE id=?")) {
      p.setString(1, message); p.setBytes(2, Database.uuidBytes(id)); p.executeUpdate();
    }
  }

  private void failRun(UUID id, String error) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status='FAILED',last_error=? WHERE id=?")) {
      p.setString(1, error); p.setBytes(2, Database.uuidBytes(id)); p.executeUpdate();
    }
  }

  private void startCall(UUID runId, String callId, String executorType, String name, JsonObject arguments,
                         boolean requiresConfirmation) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO agent_tool_calls (id,run_id,tool_call_id,executor_type,tool_name,arguments,status,"
            + "requires_confirmation,attempt_count,started_at) VALUES (?,?,?,?,?,?,'RUNNING',?,1,NOW()) "
            + "ON DUPLICATE KEY UPDATE attempt_count=attempt_count+1,status='RUNNING',started_at=NOW()")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID()));
      p.setBytes(2, Database.uuidBytes(runId));
      p.setString(3, callId);
      p.setString(4, executorType);
      p.setString(5, name);
      p.setString(6, gson.toJson(arguments));
      p.setBoolean(7, requiresConfirmation);
      p.executeUpdate();
    }
  }

  private void finishCall(String callId, String status, JsonObject result, String error) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_tool_calls SET status=?,result=?,error=?,completed_at=CASE WHEN ?='RUNNING' THEN NULL ELSE NOW() END "
            + "WHERE tool_call_id=?")) {
      p.setString(1, status);
      p.setString(2, result == null ? null : gson.toJson(result));
      p.setString(3, error);
      p.setString(4, status);
      p.setString(5, callId);
      p.executeUpdate();
    }
  }

  private RunRow run(UUID id, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT conversation_id,channel,goal,status,iteration,result,last_error FROM agent_runs "
            + "WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("agent_run_not_found");
        return new RunRow(Database.bytesUuid(rs.getBytes("conversation_id")), rs.getString("channel"),
            rs.getString("goal"), rs.getString("status"), rs.getInt("iteration"), rs.getString("result"),
            rs.getString("last_error"));
      }
    }
  }

  private JsonArray toolCalls(UUID runId) throws SQLException {
    JsonArray result = new JsonArray();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT tool_call_id,executor_type,tool_name,arguments,result,status,requires_confirmation,attempt_count,error "
            + "FROM agent_tool_calls WHERE run_id=? ORDER BY started_at,id")) {
      p.setBytes(1, Database.uuidBytes(runId));
      try (ResultSet rs = p.executeQuery()) {
        while (rs.next()) {
          JsonObject item = new JsonObject();
          item.addProperty("toolCallId", rs.getString("tool_call_id"));
          item.addProperty("executorType", rs.getString("executor_type"));
          item.addProperty("toolName", rs.getString("tool_name"));
          item.add("arguments", json(rs.getString("arguments")));
          item.add("result", json(rs.getString("result")));
          item.addProperty("status", rs.getString("status"));
          item.addProperty("requiresConfirmation", rs.getBoolean("requires_confirmation"));
          item.addProperty("attemptCount", rs.getInt("attempt_count"));
          item.addProperty("error", rs.getString("error"));
          result.add(item);
        }
      }
    }
    return result;
  }

  private void finishDraft(UUID draftId, Database.Context identity, String status, JsonObject execution)
      throws SQLException {
    try (Connection c = database.connection(); PreparedStatement find = c.prepareStatement(
        "SELECT id,result FROM agent_runs WHERE pending_draft_id=? AND workspace_id=? AND user_id=? "
            + "ORDER BY updated_at DESC LIMIT 1")) {
      find.setBytes(1, Database.uuidBytes(draftId));
      find.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      find.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = find.executeQuery()) {
        if (!rs.next()) return;
        UUID runId = Database.bytesUuid(rs.getBytes("id"));
        JsonObject result = rs.getString("result") == null ? new JsonObject()
            : JsonParser.parseString(rs.getString("result")).getAsJsonObject();
        result.add("execution", execution.deepCopy());
        result.addProperty("status", status);
        try (PreparedStatement update = c.prepareStatement(
            "UPDATE agent_runs SET status=?,result=?,pending_draft_id=NULL,completed_at=NOW() WHERE id=?")) {
          update.setString(1, status);
          update.setString(2, gson.toJson(result));
          update.setBytes(3, Database.uuidBytes(runId));
          update.executeUpdate();
        }
        try (PreparedStatement calls = c.prepareStatement(
            "UPDATE agent_tool_calls SET status=?,result=?,completed_at=NOW() "
                + "WHERE run_id=? AND status='WAITING_CONFIRMATION'")) {
          calls.setString(1, status);
          calls.setString(2, gson.toJson(execution));
          calls.setBytes(3, Database.uuidBytes(runId));
          calls.executeUpdate();
        }
        execution.addProperty("runId", runId.toString());
        execution.addProperty("runStatus", status);
      }
    }
  }

  private JsonElement json(String value) {
    return value == null ? JsonNull.INSTANCE : JsonParser.parseString(value);
  }

  private String required(JsonObject input, String name) {
    String value = string(input, name, "").trim();
    if (value.isBlank()) throw new IllegalArgumentException(name + "_required");
    return value;
  }

  private String string(JsonObject input, String name, String fallback) {
    return input.has(name) && !input.get(name).isJsonNull() ? input.get(name).getAsString() : fallback;
  }

  private record RunRow(UUID conversationId, String channel, String goal, String status, int iteration,
                        String result, String error) {}

  @FunctionalInterface
  private interface AgentWork {
    JsonObject execute() throws Exception;
  }
}
