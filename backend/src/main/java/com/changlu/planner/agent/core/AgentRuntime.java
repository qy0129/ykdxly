package com.changlu.planner.agent.core;

import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.agent.core.contract.AgentLoopState;
import com.changlu.planner.agent.subagents.document.DocumentSubagent;
import com.changlu.planner.agent.subagents.memory.MemorySubagent;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolHandler;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 主 Agent 运行循环：路由→调度→记录步骤，自动连续直到完成、需要确认或达到预算。 */
public final class AgentRuntime implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(AgentRuntime.class);
  private static final int MAX_ITERATIONS = 10;
  private static final Duration LOOP_TIMEOUT = Duration.ofMinutes(3);

  private final Database database;
  private final AiCommandService commands;
  private final AgentRouter router;
  private final com.changlu.planner.agent.core.tool.ToolRegistry tools;
  private final com.changlu.planner.agent.core.registry.SubagentRegistry subagents;
  private final DocumentSubagent documents;
  private final MemorySubagent memory;
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final ExecutorService subagentExecutions = Executors.newVirtualThreadPerTaskExecutor();
  private final Gson gson = new Gson();
  private final com.changlu.planner.agent.core.runtime.TraceRecorder traces =
      new com.changlu.planner.agent.core.runtime.TraceRecorder(LOG);
  private final com.changlu.planner.agent.core.runtime.StepRecorder stepRecorder;
  private final com.changlu.planner.agent.core.runtime.JsonSchemaValidator schemaValidator =
      new com.changlu.planner.agent.core.runtime.JsonSchemaValidator();

  public AgentRuntime(Database database, AiCommandService commands, AgentRouter router,
                      com.changlu.planner.agent.core.tool.ToolRegistry tools,
                      com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                      DocumentSubagent documents, MemorySubagent memory) {
    this.database = database;
    this.commands = commands;
    this.router = router;
    this.tools = tools;
    this.subagents = subagents;
    this.documents = documents;
    this.memory = memory;
    this.stepRecorder = new com.changlu.planner.agent.core.runtime.StepRecorder(database);
    this.tools.setObserver(new StandardToolObserver());
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
    AgentLoopState state = loadState(run);
    state.userTurns.add(message);
    saveState(runId, state);
    JsonObject request = input.deepCopy();
    reuseTaskArguments(request, state);
    request.addProperty("conversationId", run.conversationId().toString());
    return execute(runId, request, identity, run.channel(), run.iteration());
  }

  public JsonObject resumeAsync(String reference, JsonObject input, Database.Context identity) throws Exception {
    UUID runId = UUID.fromString(reference);
    RunRow run = run(runId, identity);
    validateResumable(run);
    String message = required(input, "message");
    appendGoal(runId, message);
    AgentLoopState state = loadState(run);
    state.userTurns.add(message);
    saveState(runId, state);
    markRunning(runId);
    JsonObject request = input.deepCopy();
    reuseTaskArguments(request, state);
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
    result.add("steps", stepRecorder.steps(runId));
    if (row.error() != null) result.addProperty("lastError", row.error());
    return result;
  }

  public JsonObject confirm(String draftReference, Database.Context identity) throws Exception {
    UUID draftId = UUID.fromString(draftReference);
    JsonObject result;
    try {
      result = commands.confirm(draftReference, identity);
    } catch (IllegalArgumentException | IllegalStateException error) {
      // 文生图批量草案不写入计划草案表，确认时从 Agent 运行状态恢复原始参数。
      JsonObject imageResult = confirmImageDraft(draftId, identity);
      if (imageResult != null) return imageResult;
      throw error;
    }
    RunRow run = runByPendingDraft(draftId, identity);
    workflow("确认执行", "用户操作", "计划变更", "用户确认执行待处理草案");
    if (run == null) return result;
    AgentLoopState state = loadState(run);
    state.confirmedDrafts.add(draftId.toString());
    state.pendingDraftId = null;
    state.clearPendingQuestions();
    state.appendStep("user", "confirm", "确认执行计划变更", "COMPLETED",
        "已执行 " + result.getAsJsonArray("executed").size() + " 项操作");
    saveState(run.id(), state);
    recordConfirmStep(run.id(), result);
    workflow("变更完成", "Agent消息", "计划变更", "已执行 " + result.getAsJsonArray("executed").size() + " 项操作");
    // 草案确认已经完成本次请求的唯一写操作，直接结束运行，避免重新执行原始目标生成重复草案。
    finishDraft(draftId, identity, "COMPLETED", result);
    return result;
  }

  private JsonObject confirmImageDraft(UUID draftId, Database.Context identity) throws Exception {
    RunRow run = runByPendingDraft(draftId, identity);
    if (run == null) return null;
    AgentLoopState state = loadState(run);
    if (!state.taskData.has("request") || !state.taskData.get("request").isJsonObject()) return null;

    JsonObject input = new JsonObject();
    input.addProperty("message", run.goal());
    input.addProperty("conversationId", run.conversationId().toString());
    JsonObject arguments = state.taskData.getAsJsonObject("request").deepCopy();
    // A failed command can belong to any subagent. Only image requests have count/mode.
    if (!"batch".equals(string(arguments, "mode", "")) && integer(arguments, "count", 1) <= 1) {
      return null;
    }
    arguments.addProperty("confirmed", true);
    input.add("arguments", arguments);
    AgentRouter.Decision decision = AgentRouter.Decision.execute(
        "subagent", "image.generation", "用户确认批量文生图草案");
    JsonObject generated = executeSubagent(decision, run.goal(), input, run.id(), identity, run.channel(), state);
    commands.saveAssistantMessage(run.conversationId(), string(generated, "reply", "图片已生成。"),
        imageUrls(generated));
    JsonObject result = finishRun(run.id(), run.iteration() + 1, input, decision, generated, identity);
    state.confirmedDrafts.add(draftId.toString());
    state.pendingDraftId = null;
    state.clearPendingQuestions();
    state.appendStep("user", "confirm", "确认批量文生图", "COMPLETED", "已生成图片");
    saveState(run.id(), state);
    return result;
  }

  public JsonObject cancel(String draftReference, Database.Context identity) throws Exception {
    UUID draftId = UUID.fromString(draftReference);
    JsonObject result;
    try {
      result = commands.cancel(draftReference, identity);
    } catch (IllegalArgumentException | IllegalStateException error) {
      JsonObject imageResult = cancelImageDraft(draftId, identity);
      if (imageResult != null) return imageResult;
      throw error;
    }
    finishDraft(UUID.fromString(result.get("id").getAsString()), identity, "CANCELLED", result);
    workflow("取消执行", "用户操作", "计划变更", "用户取消待处理草案");
    return result;
  }

  private JsonObject cancelImageDraft(UUID draftId, Database.Context identity) throws SQLException {
    RunRow run = runByPendingDraft(draftId, identity);
    if (run == null) return null;
    AgentLoopState state = loadState(run);
    state.pendingDraftId = null;
    state.clearPendingQuestions();
    state.appendStep("user", "cancel", "取消文生图草案", "CANCELLED", "已取消图片生成");
    saveState(run.id(), state);
    JsonObject result = new JsonObject();
    result.addProperty("id", draftId.toString());
    result.addProperty("status", "cancelled");
    result.addProperty("reply", "已取消文生图草案。");
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status='CANCELLED',result=?,pending_draft_id=NULL,completed_at=NOW() "
            + "WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setString(1, gson.toJson(result));
      p.setBytes(2, Database.uuidBytes(run.id()));
      p.setBytes(3, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(4, Database.uuidBytes(identity.userId()));
      p.executeUpdate();
    }
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
    UUID runId = null;
    String status = null;
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id,status FROM agent_runs WHERE workspace_id=? AND user_id=? AND conversation_id=? "
            + "ORDER BY updated_at DESC,id DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(2, Database.uuidBytes(identity.userId()));
      p.setBytes(3, Database.uuidBytes(conversationId));
      try (ResultSet rs = p.executeQuery()) {
        if (rs.next()) {
          runId = Database.bytesUuid(rs.getBytes("id"));
          status = rs.getString("status");
        }
      }
    }
    if (runId == null) return;
    // 兼容旧版本：草案已被确认/取消后，旧运行记录可能仍停在待确认。
    if ("WAITING_CONFIRMATION".equals(status) && !hasPendingDraft(runId, identity)) {
      markCompletedIfStale(runId, identity);
      status = "COMPLETED";
    }
    result.addProperty("runId", runId.toString());
    result.addProperty("runStatus", status);
    String rawResult = runResult(runId, identity);
    if (rawResult == null) return;
    JsonObject runResult = JsonParser.parseString(rawResult).getAsJsonObject();
    if (runResult.has("planReview")) result.add("planReview", runResult.get("planReview").deepCopy());
    if (runResult.has("data") && runResult.get("data").isJsonObject()) {
      result.add("travelData", runResult.get("data").deepCopy());
    }
    JsonObject runData = runResult.has("data") && runResult.get("data").isJsonObject()
        ? runResult.getAsJsonObject("data") : runResult;
    if (runData.has("inputRequirements")) result.add("inputRequirements", runData.get("inputRequirements").deepCopy());
    if (runData.has("formTitle")) result.add("formTitle", runData.get("formTitle").deepCopy());
  }

  private String runResult(UUID runId, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT result FROM agent_runs WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(runId));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString("result") : null; }
    }
  }

  private boolean hasPendingDraft(UUID runId, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT 1 FROM agent_runs r JOIN ai_action_drafts d ON d.id=r.pending_draft_id "
            + "WHERE r.id=? AND r.workspace_id=? AND r.user_id=? AND d.status='pending' AND d.expires_at>NOW()")) {
      p.setBytes(1, Database.uuidBytes(runId));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = p.executeQuery()) { return rs.next(); }
    }
  }

  private void markCompletedIfStale(UUID runId, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET status='COMPLETED',completed_at=COALESCE(completed_at,NOW()),pending_draft_id=NULL "
            + "WHERE id=? AND workspace_id=? AND user_id=? AND status='WAITING_CONFIRMATION'")) {
      p.setBytes(1, Database.uuidBytes(runId));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      p.executeUpdate();
    }
  }

  /** 主循环：一次调用内自动连续路由并调度执行器，直到完成、暂停等待或耗尽预算。 */
  private JsonObject execute(UUID runId, JsonObject input, Database.Context identity, String channel,
                             int currentIteration) throws Exception {
    RunRow run = run(runId, identity);
    AgentLoopState state = loadState(run);
    // 每次 execute 入口都刷新 deadline：resume/确认后续步骤时重新给足预算，只约束单次调用内的循环。
    state.deadlineEpochMs = Instant.now().plus(LOOP_TIMEOUT).toEpochMilli();
    String goal = run.goal();
    state.goal = goal;
    // 首轮用户消息也在 userTurns 中留档（resume 已单独追加过，这里按尾部去重）。
    String currentTurn = string(input, "message", goal);
    if (!currentTurn.isBlank() && (state.userTurns.isEmpty()
        || !state.userTurns.get(state.userTurns.size() - 1).equals(currentTurn))) {
      state.userTurns.add(currentTurn);
    }
    boolean skipExchange = input.has("skipExchange") && input.get("skipExchange").getAsBoolean();

    int iteration = currentIteration;
    boolean firstDispatch = true;
    while (true) {
      iteration++;
      if (iteration > MAX_ITERATIONS) {
        failRun(runId, "达到最大循环次数 " + MAX_ITERATIONS + "，已完成 " + state.steps.size() + " 步，请继续提问");
        return get(runId.toString(), identity);
      }
      if (state.pastDeadline()) {
        failRun(runId, "循环执行超时");
        return get(runId.toString(), identity);
      }
      updateRunning(runId, iteration);
      state.iteration = iteration;
      // The persisted goal contains prior turns after a resume. Only the latest turn may
      // authorize side effects such as creating a travel write draft.
      String latestMessage = currentTurn;
      workflow("收到消息", "用户消息", "待判断", latestMessage);
      AgentRouter.Decision decision;
      if (!state.pendingQuestions.isEmpty() && !state.steps.isEmpty()) {
        // WAITING_USER 恢复：用户在回答上一轮提问（如“选1”）。直接回到提问的执行器继续，
        // 避免模型路由器把简短回答误路由到无关 Subagent 而丢失对话上下文。
        decision = resumePendingExecutor(state);
      } else {
        JsonObject routeArguments = input.has("arguments") && input.get("arguments").isJsonObject()
            ? input.getAsJsonObject("arguments") : new JsonObject();
        decision = router.route(latestMessage, documents.hasAttachments(input), tools, subagents, state, routeArguments);
      }
      String route = routeName(decision.executorName());
      workflow("路由完成", "路由决策", route, compact(decision.reason()));

      if (decision.action() == AgentRouter.Action.COMPLETE) {
        String reply = decision.reply() == null || decision.reply().isBlank() ? "已完成。" : decision.reply();
        JsonObject finalResult = new JsonObject();
        finalResult.addProperty("reply", reply);
        finalResult.add("questions", new JsonArray());
        finalResult.add("actions", new JsonArray());
        // 子 Agent 的结果会先合并到 taskData；结束时必须把图片结果带回调用方。
        mergeImageData(state, finalResult);
        UUID stepId = stepRecorder.start(runId, null, "main", "complete", "complete", "任务完成", null);
        stepRecorder.finish(stepId, "COMPLETED", finalResult, reply, 0);
        state.appendStep("complete", "complete", "任务完成", "COMPLETED", reply);
        saveState(runId, state);
        if (firstDispatch && !skipExchange) {
          // 未执行任何执行器就判定完成（如普通对话），仍需落一条对话记录，否则历史为空。
          String userTurn = string(input, "message", goal);
          UUID conversationId = UUID.fromString(input.get("conversationId").getAsString());
          commands.saveExchange(conversationId, userTurn, reply, imageUrls(finalResult));
          memory.afterExchange(conversationId, userTurn, reply, identity);
        }
        JsonObject response = finishRun(runId, iteration, input, decision, finalResult, identity);
        workflow("返回消息", "Agent消息", route, reply);
        workflow("工作流结束", "系统状态", route, "已完成");
        return response;
      }

      String toolCallId = runId + ":" + iteration;
      JsonObject arguments = new JsonObject();
      arguments.addProperty("message", latestMessage);
      boolean requiresConfirmation = "tool".equals(decision.executorType())
          && tools.require(decision.executorName()).definition().requiresConfirmation();
      UUID stepId = stepRecorder.start(runId, null, "main", decision.executorType(),
          decision.executorName(), route, toolCallId);
      startCall(runId, toolCallId, decision.executorType(), decision.executorName(), arguments, requiresConfirmation);
      long startedAt = System.nanoTime();
      try {
        JsonObject result;
        if ("subagent".equals(decision.executorType())) {
          stepRecorder.setCurrentStepId(stepId);
          try {
            result = executeSubagent(decision, latestMessage, input, runId, identity, channel, state);
          } finally {
            stepRecorder.setCurrentStepId(null);
          }
        } else {
          JsonObject commandInput = input.deepCopy();
          commandInput.addProperty("message", latestMessage);
          commandInput.addProperty("skipPersistence", true);
          String documentContext = documents.planningContext(commandInput, identity, latestMessage);
          if (!documentContext.isBlank()) commandInput.addProperty("knowledgeContext", documentContext);
          String orchestrationContext = orchestrationContext(state);
          if (!orchestrationContext.isBlank()) commandInput.addProperty("orchestrationContext", orchestrationContext);
          var toolDefinition = tools.require(decision.executorName()).definition();
          var toolCall = new com.changlu.planner.agent.core.tool.ToolCall(
              toolCallId + ":tool", toolCallId, decision.executorName(), commandInput);
          var toolContext = new com.changlu.planner.agent.core.contract.AgentContext(
              runId, UUID.fromString(input.get("conversationId").getAsString()), runId.toString(),
              identity, channel, toolDefinition.requiredPermissions(),
              Instant.now().plus(toolDefinition.timeout()), state.toJson());
          // 标准工具边界执行：权限/超时/重试由 ToolRegistry 处理，observer 记录工具子步骤。
          stepRecorder.setCurrentStepId(stepId);
          try {
            result = tools.execute(toolCall, toolContext).toJson();
          } finally {
            stepRecorder.setCurrentStepId(null);
          }
        }
        long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (firstDispatch && !skipExchange) {
          // 本轮用户消息用 input.message（首次即 goal；resume 时是追加的那条），避免重复累积历史。
          String userTurn = string(input, "message", goal);
          UUID conversationId = UUID.fromString(input.get("conversationId").getAsString());
          commands.saveExchange(conversationId, userTurn, string(result, "reply", "已完成。"), imageUrls(result));
          memory.afterExchange(conversationId, userTurn, string(result, "reply", ""), identity);
        }
        firstDispatch = false;

        finishCall(toolCallId, "COMPLETED", result, null);
        recordProposedTools(runId, iteration, result);
        String pauseStatus = pauseStatus(result);
        stepRecorder.finish(stepId, pauseStatus == null ? "COMPLETED" : pauseStatus, result,
            string(result, "reply", ""), durationMs);
        state.appendStep(decision.executorType(), decision.executorName(), route,
            pauseStatus == null ? "COMPLETED" : pauseStatus, string(result, "reply", ""));
        if (pauseStatus != null) {
          // 等待用户确认/补充时也要保留 Subagent 返回的结构化数据，供 resume 继续生成草案。
          mergeTaskData(state, result);
          if ("WAITING_CONFIRMATION".equals(pauseStatus)) {
            state.pendingDraftId = draftId(result);
            state.clearPendingQuestions();
          }
          if ("WAITING_USER".equals(pauseStatus)) {
            state.clearPendingQuestions();
            if (result.has("questions") && result.get("questions").isJsonArray()) {
              result.getAsJsonArray("questions").forEach(element -> state.pendingQuestions.add(element.deepCopy()));
            }
          }
          saveState(runId, state);
          JsonObject response = finishRun(runId, iteration, input, decision, result, identity);
          workflow("返回消息", "Agent消息", route, string(result, "reply", "已完成处理"));
          workflow("工作流结束", "系统状态", route, statusName(pauseStatus));
          return response;
        }
        mergeTaskData(state, result);
        if (!state.pendingQuestions.isEmpty()) {
          // 上一轮提问已得到回答且本次未再追问，清空待回答标记，后续轮次恢复模型路由。
          state.clearPendingQuestions();
        }
        saveState(runId, state);
        workflow("返回消息", "Agent消息", route, compact(string(result, "reply", "已完成处理")));
        // 每轮执行完都刷新预算：合法多步循环不会因累计时长被误杀，防失控仍由 MAX_ITERATIONS 保证。
        state.deadlineEpochMs = Instant.now().plus(LOOP_TIMEOUT).toEpochMilli();
        // 继续循环，进入下一次路由
      } catch (Exception error) {
        stepRecorder.finish(stepId, "FAILED", null, compact(error.getMessage()),
            (System.nanoTime() - startedAt) / 1_000_000);
        finishCall(toolCallId, "FAILED", null, error.getMessage());
        failRun(runId, error.getMessage());
        workflow("执行失败", "错误消息", route, compact(error.getMessage()));
        throw error;
      }
    }
  }

  private JsonObject executeSubagent(AgentRouter.Decision decision, String message, JsonObject input,
                                     UUID runId, Database.Context identity, String channel,
                                     AgentLoopState state) throws Exception {
    com.changlu.planner.agent.core.contract.Subagent subagent = subagents.require(decision.executorName());
    UUID conversationId = UUID.fromString(input.get("conversationId").getAsString());
    String traceId = runId.toString();
    // Subagent 调模型时不读 ai_messages，这里统一注入长期记忆 + 最近对话，保证专业执行器也能结合上文。
    String sharedContext = commands.sharedContext(conversationId, identity);
    var context = new com.changlu.planner.agent.core.contract.AgentContext(runId, conversationId, traceId,
        identity, channel, Set.of("travel:read", "planning:write", "image.generate"),
        Instant.now().plus(subagent.definition().timeout()), state.toJson(), sharedContext);
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
    return agentResult.toJson();
  }

  private String orchestrationContext(AgentLoopState state) {
    if (state.steps.isEmpty() && state.taskData.keySet().isEmpty()) return "";
    StringBuilder builder = new StringBuilder();
    if (!state.steps.isEmpty()) {
      builder.append("此前已完成步骤：\n");
      for (JsonElement element : state.steps) {
        JsonObject step = element.getAsJsonObject();
        String name = string(step, "executorName", string(step, "label", ""));
        String message = string(step, "message", "已完成");
        builder.append("- ").append(name).append("：").append(message).append('\n');
      }
    }
    if (!state.taskData.keySet().isEmpty()) {
      builder.append("此前收集到的中间数据：\n").append(gson.toJson(state.taskData));
    }
    return builder.toString();
  }

  /** WAITING_USER 恢复时找到上一轮提问的执行器，跳过模型路由器直接继续，保证上下文连续。 */
  private AgentRouter.Decision resumePendingExecutor(AgentLoopState state) {
    for (int i = state.steps.size() - 1; i >= 0; i--) {
      JsonElement element = state.steps.get(i);
      if (!element.isJsonObject()) continue;
      JsonObject step = element.getAsJsonObject();
      if (!"WAITING_USER".equals(string(step, "status", ""))) continue;
      String type = string(step, "executorType", "tool");
      String name = string(step, "executorName", "planning.assistant");
      if ("tool".equals(type) || "subagent".equals(type)) {
        return AgentRouter.Decision.execute(type, name, "继续处理用户对上一轮提问的回答");
      }
    }
    return AgentRouter.Decision.execute("tool", "planning.assistant", "恢复处理待回答的提问");
  }

  private String pauseStatus(JsonObject result) {
    JsonObject draft = result.has("draft") && result.get("draft").isJsonObject()
        ? result.getAsJsonObject("draft") : null;
    if (draft != null) return "WAITING_CONFIRMATION";
    JsonArray questions = result.has("questions") && result.get("questions").isJsonArray()
        ? result.getAsJsonArray("questions") : new JsonArray();
    return questions.isEmpty() ? null : "WAITING_USER";
  }

  private String draftId(JsonObject result) {
    JsonObject draft = result.has("draft") && result.get("draft").isJsonObject()
        ? result.getAsJsonObject("draft") : null;
    return draft != null && draft.has("id") ? draft.get("id").getAsString() : null;
  }

  private void mergeTaskData(AgentLoopState state, JsonObject result) {
    if (result.has("data") && result.get("data").isJsonObject()) {
      JsonObject data = result.getAsJsonObject("data");
      for (String key : data.keySet()) state.taskData.add(key, data.get(key).deepCopy());
    }
  }

  /** 将文生图子 Agent 产生的结果暴露到最终响应，供网页和微信发送真实图片。 */
  private void mergeImageData(AgentLoopState state, JsonObject result) {
    String[] keys = {"imageUrl", "images", "requestId", "size", "style", "durationMillis"};
    for (String key : keys) {
      if (!result.has(key) && state.taskData.has(key)) {
        result.add(key, state.taskData.get(key).deepCopy());
      }
    }
  }

  /** Extract image URLs from the compatibility projection returned by AgentResult. */
  private JsonArray imageUrls(JsonObject result) {
    JsonArray urls = new JsonArray();
    if (result == null) return urls;
    addImageUrl(result, "imageUrl", urls);
    addImageRows(result, "images", urls);
    if (result.has("data") && result.get("data").isJsonObject()) {
      JsonObject data = result.getAsJsonObject("data");
      addImageUrl(data, "imageUrl", urls);
      addImageRows(data, "images", urls);
    }
    return urls;
  }

  private void addImageRows(JsonObject object, String key, JsonArray urls) {
    if (!object.has(key) || !object.get(key).isJsonArray()) return;
    for (JsonElement element : object.getAsJsonArray(key)) {
      if (element.isJsonObject()) addImageUrl(element.getAsJsonObject(), "imageUrl", urls);
    }
  }

  private void addImageUrl(JsonObject object, String key, JsonArray urls) {
    if (!object.has(key) || object.get(key).isJsonNull()) return;
    String url = object.get(key).getAsString().trim();
    if (url.isBlank()) return;
    for (JsonElement existing : urls) if (url.equals(existing.getAsString())) return;
    urls.add(url);
  }

  private void reuseTaskArguments(JsonObject request, AgentLoopState state) {
    if (request.has("arguments") || !state.taskData.has("request")
        || !state.taskData.get("request").isJsonObject()) return;
    JsonObject arguments = state.taskData.getAsJsonObject("request").deepCopy();
    if (arguments.has("budget") && arguments.get("budget").isJsonObject()
        && arguments.getAsJsonObject("budget").keySet().isEmpty()) arguments.remove("budget");
    if (arguments.has("pace") && arguments.get("pace").isJsonPrimitive()
        && arguments.get("pace").getAsString().isBlank()) arguments.remove("pace");
    if (arguments.has("preferredTransport") && arguments.get("preferredTransport").isJsonPrimitive()
        && arguments.get("preferredTransport").getAsString().isBlank()) arguments.remove("preferredTransport");
    request.add("arguments", arguments);
  }

  private void workflow(String step, String type, String route, String content) {
    LOG.info("[工作流] {}｜{}｜{}｜{}", step, type, route, compact(content));
  }

  private String routeName(String executorName) {
    if (executorName == null) return "完成";
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
    tools.close();
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

  private AgentLoopState loadState(RunRow run) {
    return run.state() != null ? run.state() : new AgentLoopState();
  }

  private void saveState(UUID runId, AgentLoopState state) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE agent_runs SET state=? WHERE id=?")) {
      p.setString(1, gson.toJson(state.toJson()));
      p.setBytes(2, Database.uuidBytes(runId));
      p.executeUpdate();
    }
  }

  private RunRow runByPendingDraft(UUID draftId, Database.Context identity) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT id,conversation_id,channel,goal,status,iteration,result,last_error,state FROM agent_runs "
            + "WHERE pending_draft_id=? AND workspace_id=? AND user_id=? ORDER BY updated_at DESC,id DESC LIMIT 1")) {
      p.setBytes(1, Database.uuidBytes(draftId));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        return row(rs);
      }
    }
  }

  private void recordConfirmStep(UUID runId, JsonObject execution) throws SQLException {
    JsonObject result = new JsonObject();
    result.add("execution", execution.deepCopy());
    UUID stepId = stepRecorder.start(runId, null, "main", "user", "confirm", "确认执行计划变更", null);
    stepRecorder.finish(stepId, "COMPLETED", result,
        "已执行 " + execution.getAsJsonArray("executed").size() + " 项操作", 0);
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
    private final Map<String, UUID> toolStepIds = new ConcurrentHashMap<>();

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
      UUID stepId = stepRecorder.start(context.runId(), stepRecorder.currentStepId(), "tool",
          "tool", definition.name(), definition.description(), call.toolCallId());
      toolStepIds.put(call.toolCallId(), stepId);
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
      UUID stepId = toolStepIds.remove(call.toolCallId());
      if (stepId != null) {
        stepRecorder.finish(stepId, status, result == null ? null : result.toJson(),
            error == null ? result.message() : error.getMessage(), durationMs);
      }
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
        "SELECT id,conversation_id,channel,goal,status,iteration,result,last_error,state FROM agent_runs "
            + "WHERE id=? AND workspace_id=? AND user_id=?")) {
      p.setBytes(1, Database.uuidBytes(id));
      p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      p.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) throw new IllegalArgumentException("agent_run_not_found");
        return row(rs);
      }
    }
  }

  private RunRow row(ResultSet rs) throws SQLException {
    String stateJson = rs.getString("state");
    return new RunRow(Database.bytesUuid(rs.getBytes("id")),
        Database.bytesUuid(rs.getBytes("conversation_id")), rs.getString("channel"),
        rs.getString("goal"), rs.getString("status"), rs.getInt("iteration"), rs.getString("result"),
        rs.getString("last_error"),
        stateJson == null ? null : AgentLoopState.fromJson(JsonParser.parseString(stateJson).getAsJsonObject()));
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
            + "ORDER BY updated_at DESC,id DESC LIMIT 1")) {
      find.setBytes(1, Database.uuidBytes(draftId));
      find.setBytes(2, Database.uuidBytes(identity.workspaceId()));
      find.setBytes(3, Database.uuidBytes(identity.userId()));
      try (ResultSet rs = find.executeQuery()) {
        if (!rs.next()) return;
        UUID runId = Database.bytesUuid(rs.getBytes("id"));
        JsonObject result = rs.getString("result") == null ? new JsonObject()
            : JsonParser.parseString(rs.getString("result")).getAsJsonObject();
        // 草案已经确认或取消后，不再把可操作的旧草案返回给客户端。
        result.remove("draft");
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

  private int integer(JsonObject input, String name, int fallback) {
    try {
      return input.has(name) && !input.get(name).isJsonNull() ? input.get(name).getAsInt() : fallback;
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private record RunRow(UUID id, UUID conversationId, String channel, String goal, String status,
                        int iteration, String result, String error, AgentLoopState state) {}

  @FunctionalInterface
  private interface AgentWork {
    JsonObject execute() throws Exception;
  }
}
