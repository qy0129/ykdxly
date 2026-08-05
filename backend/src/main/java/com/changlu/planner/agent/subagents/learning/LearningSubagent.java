package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.features.command.AiCommandService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习规划 Subagent。
 *
 * <p>职责边界：
 * <ul>
 *   <li>学习目标的全生命周期管理（创建、查询、更新、删除）</li>
 *   <li>学习进度分析和趋势报告</li>
 *   <li>基于目标的个性化学习计划建议</li>
 *   <li>知识缺口检测和领域均衡分析</li>
 * </ul>
 *
 * <p>不负责：
 * <ul>
 *   <li>普通计划/任务/待办的 CRUD → planning.assistant</li>
 *   <li>每日复盘总结 → review</li>
 *   <li>文件分析 → document</li>
 *   <li>网页搜索 → research</li>
 *   <li>排期冲突检查 → scheduling</li>
 * </ul>
 *
 * <p>通过 {@link LearningService} 访问数据，不直接操作数据库或拼接 HTTP 请求。
 * 所有写操作（创建、修改、删除）都必须先生成待确认草案。
 */
public final class LearningSubagent implements Subagent {
  private static final Logger LOG = LoggerFactory.getLogger(LearningSubagent.class);

  private final LearningService service;
  private final ModelClient model;
  private final LearningProgressTool progressTool;
  private final StudyPlanSuggestionTool suggestionTool;
  private final KnowledgeGapTool gapTool;
  private final AiCommandService commands;
  private final Gson gson = new Gson();
  private final SubagentDefinition definition = new SubagentDefinition(
      "learning", "1.0.0", "学习目标管理、学习进度分析、学习计划建议和知识领域梳理",
      List.of("学习目标", "学习进度", "学习计划", "课程", "知识梳理"), List.of(),
      new JsonObject(), new JsonObject(), Set.of(), false, true, Duration.ofSeconds(120), 2);

  public LearningSubagent(LearningService service, ModelClient model) {
    this(service, model, null);
  }

  public LearningSubagent(LearningService service, ModelClient model, AiCommandService commands) {
    this.service = service;
    this.model = model;
    this.commands = commands;
    this.progressTool = new LearningProgressTool(service);
    this.suggestionTool = new StudyPlanSuggestionTool(service, model);
    this.gapTool = new KnowledgeGapTool(service);
  }

  @Override
  public String name() {
    return "learning";
  }

  @Override
  public String description() {
    return "学习目标管理、学习进度分析、学习计划建议和知识领域梳理";
  }

  /**
   * 执行学习规划请求。
   * 根据请求内容路由到对应的工具，所有输入输出使用明确的 JSON Schema。
   *
   * @param request 用户的自然语言请求
   * @param context 自动携带的用户、工作区、会话和权限上下文
   * @return 统一格式的响应 {status, reply, data, errors?}
   */
  @Override
  public SubagentDefinition definition() { return definition; }

  @Override
  public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    String requestText = request.message();
    long startedAt = System.currentTimeMillis();
    String intent = classifyIntent(requestText);
    LOG.info("[学习规划Subagent] 执行开始 意图={} 用户={} 工作区={} 请求预览={}",
        intent, context.identity().userId(), context.identity().workspaceId(),
        requestText.length() > 100 ? requestText.substring(0, 100) + "..." : requestText);

    try {
      LearningResult result = switch (intent) {
        case "analyze_progress" -> handleAnalyzeProgress(context);
        case "suggest_plan" -> handleSuggestPlan(request, context);
        case "detect_gaps" -> handleDetectGaps(context);
        case "view_stats" -> handleViewStats(context);
        case "create_goal" -> handleCreateGoal(request, context);
        case "update_goal" -> handleUpdateGoal(request, context);
        case "delete_goal" -> handleDeleteGoal(request, context);
        default -> handleGeneral(requestText, context);
      };

      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.info("[学习规划Subagent] 执行完成 意图={} 状态={} 耗时={}毫秒",
          intent, result.status(), durationMs);
      return toAgentResult(result, context.traceId());
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.error("[学习规划Subagent] 执行失败 意图={} 耗时={}毫秒 原因={}",
          intent, durationMs, e.getMessage(), e);
      return AgentResult.failed("LEARNING_ERROR", "学习规划处理失败：" + e.getMessage(), false, context.traceId());
    }
  }

  private AgentResult toAgentResult(LearningResult result, String traceId) {
    AgentStatus status = switch (result.status()) {
      case "pending_confirmation" -> AgentStatus.WAITING_CONFIRMATION;
      case "waiting_user" -> AgentStatus.WAITING_USER;
      case "error" -> AgentStatus.FAILED;
      default -> AgentStatus.COMPLETED;
    };
    JsonObject data = result.data() == null ? new JsonObject() : result.data().deepCopy();
    boolean confirmation = status == AgentStatus.WAITING_CONFIRMATION;
    String draftId = data.has("draft") && data.get("draft").isJsonObject()
        && data.getAsJsonObject("draft").has("id")
        ? data.getAsJsonObject("draft").get("id").getAsString() : null;
    return new AgentResult("1.0", status, result.message(), data, List.of(), traceId, confirmation, draftId);
  }

  // ==================== 意图分类 ====================

  /**
   * 基于关键词分类用户意图。
   * 这是 Subagent 内部的轻量路由，不涉及主 Agent 的 if/else。
   */
  private String classifyIntent(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    // 写操作优先：请求里同时出现"创建/分析"（如"创建 Python 数据分析目标"）时不能被读意图抢走。
    if (normalized.contains("创建") || normalized.contains("新建") || normalized.contains("添加目标")
        || normalized.contains("设立")) {
      return "create_goal";
    }
    if (normalized.contains("修改") || normalized.contains("更新") || normalized.contains("调整目标")
        || normalized.contains("改到") || normalized.contains("改成") || normalized.contains("改一下")
        || normalized.contains("提前") || normalized.contains("推迟") || normalized.contains("顺延")) {
      return "update_goal";
    }
    if (normalized.contains("删除") || normalized.contains("移除") || normalized.contains("放弃")) {
      return "delete_goal";
    }
    if (normalized.contains("进度") || normalized.contains("进展") || normalized.contains("分析")) {
      return "analyze_progress";
    }
    if (normalized.contains("缺口") || normalized.contains("薄弱") || normalized.contains("知识体系")
        || normalized.contains("领域") || normalized.contains("检测")) {
      return "detect_gaps";
    }
    if (normalized.contains("统计") || normalized.contains("数据") || normalized.contains("总结")) {
      return "view_stats";
    }
    // 只有明确的"计划/方案/怎么学"才进学习计划，裸"建议/计划"不抢普通问题。
    if (normalized.contains("学习计划") || normalized.contains("制定计划") || normalized.contains("安排学习")
        || normalized.contains("怎么学") || normalized.contains("学习方案")) {
      return "suggest_plan";
    }
    return "general";
  }

  // ==================== 处理函数 ====================

  private LearningResult handleAnalyzeProgress(AgentContext context) throws Exception {
    JsonObject data = progressTool.analyze(context.identity());
    String message = buildProgressMessage(data);
    return LearningResult.success(message, data);
  }

  private LearningResult handleSuggestPlan(SubagentRequest request, AgentContext context) throws Exception {
    JsonObject input = request.arguments() == null ? new JsonObject() : request.arguments();
    JsonArray requirements = new JsonArray();
    addRequirement(requirements, input, "title", "学习目标", "text", true);
    addRequirement(requirements, input, "domain", "学习领域", "text", true);
    addRequirement(requirements, input, "targetDate", "目标日期", "date", true);
    addRequirement(requirements, input, "weeklyHours", "每周学习时长（小时）", "number", true);
    if (!requirements.isEmpty()) return informationForm(input, requirements);
    if (commands != null) {
      JsonObject fields = learningPlanFields(input);
      JsonObject action = new JsonObject(); action.addProperty("type", "create_learning_plan");
      action.addProperty("summary", "创建学习计划并拆解阶段、任务和日程"); action.add("fields", fields);
      JsonArray actions = new JsonArray(); actions.add(action);
      JsonObject draft = commands.createStructuredDraft(context.conversationId(), context.identity(), context.channel(), request.message(), "已生成学习计划草案，请确认后写入计划、任务和日程。", actions);
      JsonObject data = new JsonObject(); data.add("draft", draft.get("draft")); data.add("actions", actions); data.addProperty("planReview", true);
      return LearningResult.pendingConfirmation("已生成学习计划草案，请检查后确认。", data);
    }
    JsonObject data = suggestionTool.suggest(context.identity());
    String message = "已根据你的学习目标生成学习计划建议，请查看详情。";
    return LearningResult.success(message, data);
  }

  private LearningResult handleDetectGaps(AgentContext context) throws Exception {
    JsonObject data = gapTool.detect(context.identity());
    JsonArray gaps = data.has("gaps") ? data.getAsJsonArray("gaps") : new JsonArray();
    String message = gaps.isEmpty()
        ? "当前知识结构较为均衡，未发现明显的薄弱领域。"
        : "发现 " + gaps.size() + " 个需要关注的知识薄弱点。";
    return LearningResult.success(message, data);
  }

  private LearningResult handleViewStats(AgentContext context) throws Exception {
    LearningService.LearningStats stats = service.stats(context.identity());
    JsonObject data = new JsonObject();
    data.addProperty("generatedAt", java.time.LocalDateTime.now().toString());
    data.add("stats", stats.toJson());
    String message = String.format(
        "学习统计：%d 个活跃目标，30天内完成 %d 次学习共 %d 分钟，连续学习 %d 天。",
        stats.activeGoals(), stats.totalSessions(), stats.totalMinutes(), stats.currentStreak());
    return LearningResult.success(message, data);
  }

  /**
   * 创建学习目标——生成待确认草案。
   * 结构化参数已带 title 时直接建草案；否则走模型解析自然语言，再落库可确认草案。
   */
  private LearningResult handleCreateGoal(SubagentRequest request, AgentContext context) throws Exception {
    String requestText = request.message();
    JsonObject input = request.arguments() == null ? new JsonObject() : request.arguments();
    if (commands == null) {
      return LearningResult.error("学习规划服务不可用", "LEARNING_SERVICE_UNAVAILABLE");
    }
    // 结构化输入已带 title → 直接建草案
    if (input.has("title") && !input.get("title").isJsonNull()
        && !input.get("title").getAsString().isBlank()) {
      JsonObject action = goalAction("create_learning_goal",
          "创建学习目标：" + input.get("title").getAsString(), whitelistGoalFields(input), null);
      return createGoalDraft(context, requestText, "已生成学习目标草案，请确认后写入。", action);
    }
    if (!model.configured()) {
      return LearningResult.error("AI 模型未配置，无法生成学习目标草案",
          "请配置 PLANNER_AI_API_KEY 后重试");
    }
    try {
      JsonObject modelContext = goalDraftContext(requestText, context);
      JsonArray draftMessages = LearningPrompt.goalDraftMessages(modelContext);
      appendSharedContext(draftMessages, context);
      // 默认 60s 超时 + 2 次重试，避免慢模型在 25s 内未返回被误判为失败。
      JsonObject aiResult = model.completeJson("learning-goal-draft",
          draftMessages, 0.3, 1200);
      JsonObject goal = aiResult.has("draft") && aiResult.get("draft").isJsonObject()
          ? aiResult.getAsJsonObject("draft") : aiResult;
      String title = string(goal, "title", "");
      if (title.isBlank()) {
        return waitingQuestion(
            "请描述你想创建的学习目标，包括名称和学习领域，例如「创建学习目标：今年把英语四级考到 600 分」。");
      }
      JsonObject fields = whitelistGoalFields(goal);
      if (!fields.has("title")) fields.addProperty("title", title);
      if (aiResult.has("rationale") && !aiResult.get("rationale").isJsonNull()) {
        fields.addProperty("reason", aiResult.get("rationale").getAsString());
      }
      JsonObject action = goalAction("create_learning_goal", "创建学习目标：" + title, fields, null);
      return createGoalDraft(context, requestText, "已生成学习目标草案：「" + title + "」，请确认后写入。", action);
    } catch (Exception e) {
      LOG.warn("[学习规划] AI草案生成失败 原因={}", e.getMessage());
      return LearningResult.error("生成学习目标草案失败，请重试", e.getMessage());
    }
  }

  private LearningResult handleUpdateGoal(SubagentRequest request, AgentContext context) throws Exception {
    if (commands == null) return LearningResult.error("学习规划服务不可用", "LEARNING_SERVICE_UNAVAILABLE");
    String requestText = request.message();
    JsonObject input = request.arguments() == null ? new JsonObject() : request.arguments();
    var goals = service.listGoals(context.identity());
    LearningService.LearningGoal target = resolveGoal(requestText, input, goals);
    if (target == null) {
      if (goals.isEmpty()) return LearningResult.success("当前没有可修改的学习目标，可以先创建一个。", new JsonObject());
      return waitingQuestion("要修改哪个学习目标？" + numberedGoalList(goals));
    }
    if (!model.configured()) {
      return LearningResult.error("AI 模型未配置，无法生成修改草案", "请配置 PLANNER_AI_API_KEY 后重试");
    }
    try {
      JsonObject modelContext = new JsonObject();
      modelContext.addProperty("request", requestText);
      modelContext.addProperty("currentDate", LocalDate.now().toString());
      modelContext.add("targetGoal", target.toJson());
      JsonArray draftMessages = LearningPrompt.goalUpdateMessages(modelContext);
      appendSharedContext(draftMessages, context);
      JsonObject aiResult = model.completeJson("learning-goal-update", draftMessages, 0.3, 1200);
      JsonObject fields = aiResult.has("fields") && aiResult.get("fields").isJsonObject()
          ? whitelistGoalFields(aiResult.getAsJsonObject("fields")) : new JsonObject();
      if (fields.size() == 0 || (fields.size() == 1 && fields.has("reason"))) {
        return waitingQuestion(
            "请告诉我要修改「" + target.title() + "」的哪些内容，例如目标日期、每周时长或优先级。");
      }
      JsonObject action = goalAction("update_learning_goal", "调整学习目标：" + target.title(), fields,
          target.id());
      return createGoalDraft(context, requestText, "已生成学习目标修改草案，请确认后写入。", action);
    } catch (Exception e) {
      LOG.warn("[学习规划] 修改草案生成失败 原因={}", e.getMessage());
      return LearningResult.error("生成学习目标修改草案失败，请重试", e.getMessage());
    }
  }

  private LearningResult handleDeleteGoal(SubagentRequest request, AgentContext context) throws Exception {
    if (commands == null) return LearningResult.error("学习规划服务不可用", "LEARNING_SERVICE_UNAVAILABLE");
    String requestText = request.message();
    JsonObject input = request.arguments() == null ? new JsonObject() : request.arguments();
    var goals = service.listGoals(context.identity());
    LearningService.LearningGoal target = resolveGoal(requestText, input, goals);
    if (target == null) {
      if (goals.isEmpty()) return LearningResult.success("当前没有可删除的学习目标。", new JsonObject());
      return waitingQuestion("要删除哪个学习目标？" + numberedGoalList(goals));
    }
    JsonObject fields = new JsonObject();
    fields.addProperty("reason", "用户确认删除学习目标");
    JsonObject action = goalAction("delete_learning_goal", "删除学习目标：" + target.title(), fields,
        target.id());
    return createGoalDraft(context, requestText, "已生成学习目标删除草案（不可逆），请确认后执行。", action);
  }

  /**
   * 通用处理：将请求交给 AI 模型综合分析。
   * 不无限扩大上下文——只加载必要的学习数据。
   */
  private LearningResult handleGeneral(String request, AgentContext context) throws Exception {
    if (!model.configured()) {
      // 无模型时返回基于规则的分析
      var stats = service.stats(context.identity());
      var goals = service.listGoals(context.identity());
      JsonObject data = new JsonObject();
      data.add("stats", stats.toJson());
      JsonArray goalsJson = new JsonArray();
      for (var goal : goals) goalsJson.add(goal.toJson());
      data.add("goals", goalsJson);
      data.addProperty("note", "AI 模型未配置，显示基础学习数据。配置 api.key 后可获得智能分析。");
      return LearningResult.success("当前学习概况（基础模式）", data);
    }

    // 构建精简上下文
    var goals = service.listGoals(context.identity());
    var stats = service.stats(context.identity());
    var sessions = service.listSessions(context.identity(), 7);

    JsonObject modelContext = new JsonObject();
    JsonArray goalsJson = new JsonArray();
    for (var goal : goals) goalsJson.add(goal.toJson());
    modelContext.add("goals", goalsJson);
    modelContext.add("stats", stats.toJson());
    JsonArray sessionsJson = new JsonArray();
    for (var session : sessions) sessionsJson.add(session.toJson());
    modelContext.add("recentSessions", sessionsJson);
    modelContext.addProperty("userRequest", request);

    try {
      JsonArray messages = new JsonArray();
      messages.add(ModelClient.message("system", LearningPrompt.SYSTEM_PROMPT
          + "\n请综合分析以下学习数据并回答用户的问题。只输出 JSON："
          + "{\"reply\":\"综合分析结果\",\"highlights\":[\"亮点\"],\"concerns\":[\"需要关注的点\"]}"));
      appendSharedContext(messages, context);
      messages.add(ModelClient.message("user", modelContext.toString()));

      JsonObject aiResult = model.completeJson("learning-general", messages, 0.3, 1000);
      String reply = aiResult.has("reply") ? aiResult.get("reply").getAsString() : "分析完成";

      JsonObject data = new JsonObject();
      data.add("stats", stats.toJson());
      data.add("analysis", aiResult);

      return LearningResult.success(reply, data);
    } catch (Exception e) {
      LOG.warn("[学习规划] 通用分析失败 原因={}", e.getMessage());
      // 优雅降级：返回基础数据
      JsonObject data = new JsonObject();
      data.add("stats", stats.toJson());
      return LearningResult.success(
          "学习数据已加载（AI 分析暂时不可用：" + e.getMessage() + "）", data);
    }
  }

  // ==================== 辅助方法 ====================

  private String buildProgressMessage(JsonObject data) {
    if (!data.has("goals")) return "学习进度数据已加载。";
    JsonArray goals = data.getAsJsonArray("goals");
    if (goals.isEmpty()) return "尚未创建学习目标。建议先添加一个学习目标开始规划。";
    long active = 0;
    long nearComplete = 0;
    for (int i = 0; i < goals.size(); i++) {
      JsonObject g = goals.get(i).getAsJsonObject();
      if ("active".equals(g.get("status").getAsString())) active++;
      if (g.get("progress").getAsDouble() >= 80) nearComplete++;
    }
    StringBuilder msg = new StringBuilder();
    msg.append("当前 ").append(active).append(" 个活跃学习目标");
    if (nearComplete > 0) msg.append("，其中 ").append(nearComplete).append(" 个接近完成");
    msg.append("。");
    return msg.toString();
  }

  private JsonObject errorItem(String field, String message) {
    JsonObject item = new JsonObject();
    item.addProperty("field", field);
    item.addProperty("message", message);
    return item;
  }

  /** 追问型等待：必须带 questions，否则 AgentRuntime 会把它当 COMPLETED 结束 run，无法继续同一条对话。 */
  private LearningResult waitingQuestion(String message) {
    JsonObject data = new JsonObject();
    JsonArray questions = new JsonArray();
    questions.add(message);
    data.add("questions", questions);
    return LearningResult.waitingUser(message, data);
  }

  private LearningResult informationForm(JsonObject request, JsonArray requirements) {
    JsonObject data = new JsonObject(); data.add("request", request.deepCopy());
    data.addProperty("formTitle", "信息搜集表"); data.add("inputRequirements", requirements);
    JsonArray questions = new JsonArray(); questions.add("请补充学习信息，AI 会结合表单和备注生成学习计划。"); data.add("questions", questions);
    return LearningResult.waitingUser("请先补充学习信息。", data);
  }

  /** 通过 createStructuredDraft 落库可确认草案，返回带真实 draft.id 的待确认结果。 */
  private LearningResult createGoalDraft(AgentContext context, String requestText, String reply,
                                         JsonObject action) throws Exception {
    JsonArray actions = new JsonArray(); actions.add(action);
    JsonObject draft = commands.createStructuredDraft(context.conversationId(), context.identity(),
        context.channel(), requestText, reply, actions);
    JsonObject data = new JsonObject(); data.add("draft", draft.get("draft")); data.add("actions", actions);
    return LearningResult.pendingConfirmation(reply, data);
  }

  private JsonObject goalAction(String type, String summary, JsonObject fields, String targetId) {
    JsonObject action = new JsonObject();
    action.addProperty("type", type);
    action.addProperty("summary", summary);
    action.add("fields", fields);
    if (targetId != null) action.addProperty("targetId", targetId);
    return action;
  }

  /** 只保留学习目标动作允许的字段，丢弃模型输出里的扩展键（如 milestones）。 */
  private JsonObject whitelistGoalFields(JsonObject source) {
    JsonObject fields = new JsonObject();
    for (String key : List.of("title", "description", "domain", "priority", "targetDate",
        "weeklyHours", "status", "planId", "reason")) {
      if (source.has(key) && !source.get(key).isJsonNull()) fields.add(key, source.get(key).deepCopy());
    }
    return fields;
  }

  private JsonObject goalDraftContext(String requestText, AgentContext context) throws Exception {
    JsonObject modelContext = new JsonObject();
    modelContext.addProperty("request", requestText);
    // 让模型知道"今天"，避免把"今年/年底"等相对时间算成训练期年份。
    modelContext.addProperty("currentDate", LocalDate.now().toString());
    JsonArray existing = new JsonArray();
    for (var goal : service.listGoals(context.identity())) {
      JsonObject g = new JsonObject();
      g.addProperty("title", goal.title());
      g.addProperty("domain", goal.domain());
      g.addProperty("status", goal.status());
      existing.add(g);
    }
    modelContext.add("existingGoals", existing);
    JsonArray knownAreas = new JsonArray();
    for (var area : service.listKnowledgeAreas(context.identity())) knownAreas.add(area.name());
    modelContext.add("knownAreas", knownAreas);
    return modelContext;
  }

  /** 按显式 goalId、标题/领域模糊匹配或序号解析目标；0 个或多个命中时返回 null 由调用方追问。 */
  private LearningService.LearningGoal resolveGoal(String message, JsonObject args,
                                                   List<LearningService.LearningGoal> goals) {
    if (goals == null || goals.isEmpty()) return null;
    if (args != null && args.has("goalId") && !args.get("goalId").isJsonNull()) {
      try {
        UUID id = UUID.fromString(args.get("goalId").getAsString());
        for (var goal : goals) if (id.toString().equals(goal.id())) return goal;
      } catch (IllegalArgumentException ignored) { }
    }
    String normalized = message == null ? "" : message.replaceAll("\\s", "").toLowerCase();
    Matcher numeric = Pattern.compile("^(?:第)?([0-9一二三四五六七八九十①②③④⑤⑥⑦⑧⑨⑩]+)(?:个|个目标)?$")
        .matcher(normalized);
    if (numeric.matches()) {
      int index = parseIndex(numeric.group(1));
      if (index >= 1 && index <= goals.size()) return goals.get(index - 1);
      return null;
    }
    // 最长公共子串打分：取与消息共享片段最长的目标；<3 字（如"目标/学习"）是泛词，不算唯一命中。
    LearningService.LearningGoal best = null;
    int bestLength = 0;
    boolean ambiguous = false;
    for (var goal : goals) {
      String title = normalize(goal.title());
      String domain = normalize(goal.domain());
      int length = Math.max(longestShared(normalized, title), longestShared(normalized, domain));
      if (length > bestLength) {
        bestLength = length;
        best = goal;
        ambiguous = false;
      } else if (length == bestLength && bestLength >= 3 && best != null) {
        ambiguous = true; // 并列命中最长片段 → 交给调用方让用户明确选择
      }
    }
    if (ambiguous) return null;
    if (bestLength >= 3) return best;
    // 追问场景：resume 会把整段 goal（含追加的回答）作为 message 传入，
    // 用户回答"1"时消息形如"删除学习目标\n1"，提取末尾数字作为序号。
    Matcher numericTail = Pattern.compile("(\\d+|[一二三四五六七八九十①②③④⑤⑥⑦⑧⑨⑩]+)$").matcher(normalized);
    if (numericTail.find()) {
      int index = parseIndex(numericTail.group(1));
      if (index >= 1 && index <= goals.size()) return goals.get(index - 1);
    }
    return null;
  }

  private String normalize(String value) {
    return value == null ? "" : value.replaceAll("\\s", "").toLowerCase();
  }

  /** 两个短字符串的最长公共子串长度。 */
  private int longestShared(String a, String b) {
    int best = 0;
    for (int i = 0; i < a.length(); i++) {
      for (int j = 0; j < b.length(); j++) {
        int k = 0;
        while (i + k < a.length() && j + k < b.length() && a.charAt(i + k) == b.charAt(j + k)) k++;
        if (k > best) best = k;
      }
    }
    return best;
  }

  private int parseIndex(String value) {
    if (value == null || value.isEmpty()) return 0;
    char c = value.charAt(0);
    if (c >= '0' && c <= '9') {
      try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { return 0; }
    }
    return switch (c) {
      case '一', '①' -> 1; case '二', '两', '②' -> 2; case '三', '③' -> 3; case '四', '④' -> 4;
      case '五', '⑤' -> 5; case '六', '⑥' -> 6; case '七', '⑦' -> 7; case '八', '⑧' -> 8;
      case '九', '⑨' -> 9; case '十', '⑩' -> 10;
      default -> 0;
    };
  }

  private String numberedGoalList(List<LearningService.LearningGoal> goals) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < goals.size(); i++) {
      var goal = goals.get(i);
      sb.append('\n').append(i + 1).append(". ").append(goal.title());
      if (goal.domain() != null && !goal.domain().isBlank()) {
        sb.append("（").append(goal.domain()).append("）");
      }
    }
    return sb.toString();
  }

  private String string(JsonObject value, String name, String fallback) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : fallback;
  }

  private void addRequirement(JsonArray requirements, JsonObject input, String field, String label, String type, boolean required) {
    if (input.has(field) && !input.get(field).isJsonNull()) {
      JsonElement value = input.get(field);
      if (value.isJsonPrimitive() && !value.getAsString().isBlank()) return;
    }
    JsonObject item = new JsonObject(); item.addProperty("field", field); item.addProperty("label", label); item.addProperty("type", type); item.addProperty("required", required); requirements.add(item);
  }

  private JsonObject learningPlanFields(JsonObject input) {
    JsonObject fields = new JsonObject();
    String title = input.has("title") ? input.get("title").getAsString() : "学习计划";
    String domain = input.has("domain") ? input.get("domain").getAsString() : "general";
    fields.addProperty("title", title + "学习计划"); fields.addProperty("description", "由学习规划 Agent 根据目标拆解的学习计划");
    fields.addProperty("color", "#72806A"); fields.addProperty("dueDate", input.get("targetDate").getAsString()); fields.addProperty("reason", "learning_agent");
    JsonObject goal = input.deepCopy(); goal.addProperty("title", title); goal.addProperty("domain", domain); fields.add("learningGoal", goal);
    JsonArray stages = new JsonArray();
    String[] names = {"基础与资料准备", "集中练习与巩固", "项目实践与复盘"};
    LocalDate scheduleDate = LocalDate.now().plusDays(1);
    for (int i = 0; i < names.length; i++) {
      JsonObject stage = new JsonObject(); stage.addProperty("title", names[i]);
      stage.addProperty("dueDate", scheduleDate.plusDays(i * 2L).toString());
      JsonArray tasks = new JsonArray(); JsonObject task = new JsonObject(); task.addProperty("title", title + " - " + names[i]);
      task.addProperty("description", "围绕" + domain + "完成本阶段学习并记录结果"); task.addProperty("priority", i == 0 ? "high" : "medium"); task.addProperty("estimatedMinutes", 120);
      task.addProperty("dueAt", scheduleDate.plusDays(i * 2L) + "T23:00:00");
      JsonArray schedules = new JsonArray(); JsonObject schedule = new JsonObject(); schedule.addProperty("title", title + "学习时段"); schedule.addProperty("startAt", scheduleDate.plusDays(i * 2L) + "T20:00:00"); schedule.addProperty("durationMinutes", 120); schedules.add(schedule); task.add("schedules", schedules);
      tasks.add(task); stage.add("tasks", tasks); stages.add(stage);
    }
    fields.add("stages", stages); return fields;
  }

  /** 把长期记忆与最近对话合并进第一条 system 提示（SiliconFlow 只允许单条 system 且必须在开头）。 */
  private void appendSharedContext(JsonArray messages, AgentContext context) {
    String shared = context.sharedContext();
    if (shared == null || shared.isBlank()) return;
    String suffix = "\n\n已知的用户长期记忆与最近对话：\n" + shared;
    for (int index = 0; index < messages.size(); index++) {
      JsonElement element = messages.get(index);
      if (!element.isJsonObject()) continue;
      JsonObject message = element.getAsJsonObject();
      if (element.getAsJsonObject().has("role") && "system".equals(message.get("role").getAsString())) {
        String content = message.has("content") && !message.get("content").isJsonNull()
            ? message.get("content").getAsString() : "";
        message.addProperty("content", content + suffix);
        return;
      }
    }
  }
}
