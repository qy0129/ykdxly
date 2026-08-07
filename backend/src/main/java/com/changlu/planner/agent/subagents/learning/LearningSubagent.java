package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.shared.database.Database;
import com.changlu.planner.agent.subagents.learning.tools.LearningResearchTool;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
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
  private final com.changlu.planner.agent.core.tool.ToolRegistry tools;
  private final Gson gson = new Gson();
  private final SubagentDefinition definition = new SubagentDefinition(
      "learning", "1.0.0", "学习目标管理、学习进度分析、学习计划建议和知识领域梳理",
      List.of("学习目标", "学习进度", "学习计划", "课程", "知识梳理"), List.of(),
      new JsonObject(), new JsonObject(), Set.of(LearningResearchTool.NAME), true, true,
      // 长周期目标按 30 天一块分块展开逐日任务，需要跨多次模型调用：预算提到 480s（紧凑大纲 + 约 10 块），
      // 给 SiliconFlow 高峰时的慢响应留出余量，避免 subagent 预算墙在草案落库前截断。
      // 主循环每轮完成后会刷新自身预算，单次子代理调用不会撞 3 分钟主循环墙。
      Duration.ofSeconds(480), 2);

  public LearningSubagent(LearningService service, ModelClient model) {
    this(service, model, null, null);
  }

  public LearningSubagent(LearningService service, ModelClient model, AiCommandService commands) {
    this(service, model, commands, null);
  }

  public LearningSubagent(LearningService service, ModelClient model, AiCommandService commands,
                          com.changlu.planner.agent.core.tool.ToolRegistry tools) {
    this.service = service;
    this.model = model;
    this.commands = commands;
    this.tools = tools;
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
  /** 意图分类：包级可见便于测试直接断言；仅执行路由内部使用。 */
  String classifyIntent(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    // 修改意图优先于创建：草案修改消息（如"把目标日期改成明年5月"）没有创建动词时直接落 update_goal，
    // 避免被误判成创建而重新建一份重复目标。但消息里同时出现"创建/新建/设立"且携带"把X改成Y"这类内联
    // 约束时仍是创建（如"创建学习目标：雅思7分，把每周时长改成10小时"），不能被修改词抢走。
    boolean createVerb = normalized.contains("创建") || normalized.contains("新建")
        || normalized.contains("添加目标") || normalized.contains("设立");
    boolean updateVerb = normalized.contains("修改") || normalized.contains("更新")
        || normalized.contains("调整目标") || normalized.contains("改到")
        || normalized.contains("改成") || normalized.contains("改一下")
        || normalized.contains("提前") || normalized.contains("推迟")
        || normalized.contains("顺延");
    if (updateVerb && !createVerb) {
      return "update_goal";
    }
    // 写操作优先：请求里同时出现"创建/分析"（如"创建 Python 数据分析目标"）时不能被读意图抢走。
    if (createVerb) {
      return "create_goal";
    }
    // 没有"创建"字样但表达"想学/要学X，达到/考到Y分"的创建目标意图。
    // 例如「我现在想要学高数，学7天，希望实现期末考试达到95分」→ create_goal，
    // 否则会落到 handleGeneral 只输出散文、不真正建草案，用户确认时丢失上下文。
    boolean wantsLearn = normalized.contains("想学") || normalized.contains("要学")
        || normalized.contains("打算学") || normalized.contains("准备学")
        || normalized.contains("开始学") || normalized.contains("想考")
        || normalized.contains("要考")
        // 「90天内系统学会Python数据分析，每周8小时」这类自然说法没有"创建/想学/要考"，
        // 但"学会/学成/掌握"是明确的学习目标信号；否则"数据分析"里的"分析"会被误判成进度分析。
        || normalized.contains("学会") || normalized.contains("学成")
        || normalized.contains("学完") || normalized.contains("精通")
        || normalized.contains("掌握");
    boolean hasTarget = normalized.contains("达到") || normalized.contains("考到")
        || normalized.contains("分") || normalized.contains("目标")
        // 「每周8小时/每天学习」这类时长安排本身是创建计划的目标信号（无"达到X分"指标时兜底）。
        || normalized.contains("每周") || normalized.contains("每天")
        || normalized.contains("每日");
    boolean isQuestion = normalized.contains("怎么学") || normalized.contains("怎么")
        || normalized.contains("如何") || normalized.contains("吗")
        || normalized.endsWith("？") || normalized.endsWith("?");
    if (wantsLearn && hasTarget && !isQuestion) {
      return "create_goal";
    }
    if (updateVerb) {
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
    if (!requirements.isEmpty()) {
      // 创建目标时会自动生成每日学习计划，引导用户走创建目标，避免前端无法提交的学习表单死路。
      return waitingQuestion(
          "请先创建一个学习目标（例如「创建学习目标：明年 6 月雅思 7 分，每周 10 小时」），创建时会自动联网调研并生成每日学习计划。");
    }
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
   * 创建学习目标——联网调研领域，模型生成量化指标与课程大纲，逐日展开成每日学习计划，一并写入规划。
   * 全部走待确认草案，用户确认后落库。
   */
  private LearningResult handleCreateGoal(SubagentRequest request, AgentContext context) throws Exception {
    String requestText = request.message();
    JsonObject input = request.arguments() == null ? new JsonObject() : request.arguments();
    if (commands == null) {
      return LearningResult.error("学习规划服务不可用", "LEARNING_SERVICE_UNAVAILABLE");
    }
    // 结构化输入已带 title → 直接建目标草案（不含每日计划）
    if (input.has("title") && !input.get("title").isJsonNull()
        && !input.get("title").getAsString().isBlank()) {
      String title = input.get("title").getAsString();
      LearningService.LearningGoal existing = findDuplicateGoal(context, title,
          string(input, "domain", ""), parseDate(input, "targetDate"));
      if (existing != null) {
        return LearningResult.success(existingGoalNotice(existing), new JsonObject());
      }
      JsonObject action = goalAction("create_learning_goal",
          "创建学习目标：" + title, whitelistGoalFields(input), null);
      return createGoalDraft(context, requestText, "已生成学习目标草案，请确认后写入。", action);
    }
    if (!model.configured()) {
      return LearningResult.error("AI 模型未配置，无法生成学习目标草案",
          "请配置 PLANNER_AI_API_KEY 后重试");
    }
    try {
      // 1. 联网调研目标领域，拿公开资料
      JsonArray sources = researchSources(requestText, context);
      // 2. 模型生成课程大纲（量化指标 + 里程碑 + 阶段 + 每日模板）
      JsonObject curriculum = requestCurriculum(requestText, input, sources, context);
      JsonObject goal = curriculum.has("goal") && curriculum.get("goal").isJsonObject()
          ? curriculum.getAsJsonObject("goal") : curriculum;
      String title = string(goal, "title", "");
      if (title.isBlank()) {
        return waitingQuestion(
            "请描述你想创建的学习目标，包括名称和学习领域，例如「创建学习目标：今年把英语四级考到 600 分」。");
      }
      LocalDate targetDate = parseDate(goal, "targetDate");
      // 2.5. 已有同主题活跃目标时不重复创建，避免确认时每日日程互相冲突（schedule_conflict）。
      LearningService.LearningGoal existing = findDuplicateGoal(context, title,
          string(goal, "domain", ""), targetDate);
      if (existing != null) {
        return LearningResult.success(existingGoalNotice(existing), new JsonObject());
      }
      // 3a. 没有明确目标日期 → 只创建目标草案（不生成每日计划）
      if (targetDate == null || targetDate.isBefore(LocalDate.now())) {
        JsonObject fields = whitelistGoalFields(goal);
        if (!fields.has("title")) fields.addProperty("title", title);
        if (curriculum.has("targetMetrics")) fields.add("targetMetrics", curriculum.get("targetMetrics").deepCopy());
        if (curriculum.has("milestones")) fields.add("milestones", curriculum.get("milestones").deepCopy());
        JsonObject action = goalAction("create_learning_goal", "创建学习目标：" + title, fields, null);
        return createGoalDraft(context, requestText, "已生成学习目标草案：「" + title + "」，请确认后写入。", action);
      }
      // 3b. 有目标日期 → 逐日展开每日学习计划，写入规划
      JsonObject planFields = dailyPlanFields(curriculum, goal, title, targetDate, input, context);
      JsonObject action = goalAction("create_learning_plan", "创建学习计划：" + title, planFields, null);
      String reply = "已生成学习目标与每日学习计划草案：「" + title + "」，确认后写入计划并按天推进。";
      return createGoalDraft(context, requestText, reply, action);
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
      // 待确认草案阶段的目标还没落库（listGoals 查不到）。若运行状态里有上一版草案（modifyDraft 快照成
      // previousPlan），说明是"改草案"而不是改已存在的目标：把原目标摘要 + 修改句合并后重新走课程大纲
      // 生成，让修改融入新草案，而不是回一句"当前没有可修改的学习目标"。
      JsonObject previousPlan = previousPlanData(context);
      if (previousPlan != null && goals.isEmpty()) {
        return regenerateDraft(request, context, previousPlan);
      }
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

  /**
   * 检测是否已存在同主题的活跃学习目标。
   * 命中条件：目标日期相近（±90 天）且（领域一致 或 去噪后标题共享片段 ≥4 字）。
   * 避免重复创建目标+每日计划，否则确认时 330 条每日日程会与旧计划重叠，被 schedule_conflict 整单拦截。
   * 标题先剥离"三个月后"这类时间前缀和"目标 95 分"这类标注，否则"三个月"公共词会触发阈值误判
   * 不同科目（如高数 vs 英语四级）为重复，导致 bot 拒绝创建而前端无数据。
   */
  private LearningService.LearningGoal findDuplicateGoal(AgentContext context, String title,
                                                         String domain, LocalDate targetDate) throws Exception {
    if (title == null || title.isBlank() || targetDate == null) return null;
    var goals = service.listGoals(context.identity());
    if (goals.isEmpty()) return null;
    String normalizedTitle = normalize(stripGoalNoise(title));
    String normalizedDomain = normalize(domain);
    for (var goal : goals) {
      if (!"active".equals(goal.status()) || goal.targetDate() == null) continue;
      long days = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(targetDate, goal.targetDate()));
      if (days > 90) continue;
      String goalDomain = normalize(goal.domain());
      boolean sameDomain = !normalizedDomain.isBlank() && !goalDomain.isBlank()
          && (goalDomain.contains(normalizedDomain) || normalizedDomain.contains(goalDomain));
      String goalTitle = normalize(stripGoalNoise(goal.title()));
      boolean similarTitle = !normalizedTitle.isBlank() && !goalTitle.isBlank()
          && longestShared(normalizedTitle, goalTitle) >= 4;
      if (sameDomain || similarTitle) return goal;
    }
    return null;
  }

  /** 剥离标题里的时间跨度前缀（三个月/一年/几周等）与"目标 X 分"等标注，只留学科核心词，避免去重误判。 */
  private String stripGoalNoise(String value) {
    if (value == null) return "";
    String t = value.replaceAll("\\s", "");
    // 时间前缀：数字或中文数字 + 时间单位（个月/年/周/天），后接 内/后/里 等，整体剥掉。
    t = t.replaceAll("^(\\d+\\.?\\d*|[一二三四五六七八九十两半多近约])\\s*(个月|月|年|周|星期|天|日)(内|后|里|之内|以后|以内|之内完成)?", "");
    // 中文数字开头的"三个月"重复剥一次（上面正则覆盖不全时兜底）。
    t = t.replaceAll("^(一|二|三|四|五|六|七|八|九|十|两|几|半)\\s*(个月|月|年|周|星期|天)", "");
    // "目标 95 分"、"目标 600 分" 及 "(目标...)" 括号标注。
    t = t.replaceAll("目标\\s*\\d+\\s*分", "");
    t = t.replaceAll("[（(]目标[^）)]*[）)]", "");
    // "每周 X 小时"、"每天 X 小时" 时长标注。
    t = t.replaceAll("(每|每天|每周)\\s*\\d+\\.?\\d*\\s*小时", "");
    return t;
  }

  /** 已有目标时的提示文案，指引用户修改而不是重复创建。 */
  private String existingGoalNotice(LearningService.LearningGoal goal) {
    return "你已有一个活跃的学习目标「" + goal.title() + "」"
        + (goal.targetDate() != null ? "（目标日期 " + goal.targetDate() + "）" : "")
        + (goal.weeklyHours() != null ? "，每周 " + goal.weeklyHours() + " 小时" : "")
        + "。为避免生成重复的计划和日程，我没有重复创建；如需调整可以说「修改" + goal.title() + "」。";
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

  /** 从运行状态取上一版学习草案的 actions（modifyDraft 会把它快照成 taskData.previousPlan）。 */
  private JsonObject previousPlanData(AgentContext context) {
    JsonObject state = context.taskState();
    if (state == null || !state.has("taskData") || !state.get("taskData").isJsonObject()) return null;
    JsonObject taskData = state.getAsJsonObject("taskData");
    if (!taskData.has("previousPlan") || !taskData.get("previousPlan").isJsonArray()) return null;
    return taskData.deepCopy();
  }

  /** 草案修改：原目标未落库，取上一版草案的目标摘要，合并修改句后重新走课程大纲生成与新草案。 */
  private LearningResult regenerateDraft(SubagentRequest request, AgentContext context,
                                         JsonObject taskData) throws Exception {
    JsonArray plan = taskData.getAsJsonArray("previousPlan");
    JsonObject action = plan.size() > 0 && plan.get(0).isJsonObject() ? plan.get(0).getAsJsonObject() : null;
    JsonObject fields = action != null && action.has("fields") && action.get("fields").isJsonObject()
        ? action.getAsJsonObject("fields") : new JsonObject();
    JsonObject goal = fields.has("learningGoal") && fields.get("learningGoal").isJsonObject()
        ? fields.getAsJsonObject("learningGoal") : fields;
    String title = string(goal, "title", string(fields, "title", ""));
    String domain = string(goal, "domain", string(fields, "domain", ""));
    String targetDate = string(goal, "targetDate", string(fields, "targetDate", ""));
    Double weeklyHours = goal.has("weeklyHours") && goal.get("weeklyHours").isJsonPrimitive()
        ? goal.get("weeklyHours").getAsDouble() : null;
    // 把原计划要点 + 修改句合并成重生成请求，让修改真正到达模型；结构化参数里带 title 会触发
    // handleCreateGoal 的快速路径（跳过模型），把修改文字吞掉重新建一份一模一样的目标。
    StringBuilder combined = new StringBuilder();
    if (!title.isBlank()) combined.append(title).append("学习计划");
    if (!domain.isBlank()) combined.append("，领域：").append(domain);
    if (!targetDate.isBlank()) combined.append("，目标日期：").append(targetDate);
    if (weeklyHours != null) combined.append("，每周时长：").append(weeklyHours);
    combined.append("\n用户要求修改：").append(request.message());
    JsonObject safeArgs = request.arguments() == null ? new JsonObject() : request.arguments().deepCopy();
    safeArgs.remove("title");
    safeArgs.remove("targetDate");
    // 复用创建流程：联网调研 + 课程大纲 + 逐日学习计划，全部重新生成并写入新草案。
    return handleCreateGoal(new SubagentRequest(combined.toString(), safeArgs, request.documentIds()), context);
  }

  private JsonObject goalAction(String type, String summary, JsonObject fields, String targetId) {
    JsonObject action = new JsonObject();
    action.addProperty("type", type);
    action.addProperty("summary", summary);
    action.add("fields", fields);
    if (targetId != null) action.addProperty("targetId", targetId);
    return action;
  }

  /** 只保留学习目标动作允许的字段，丢弃模型输出里的无关扩展键。 */
  private JsonObject whitelistGoalFields(JsonObject source) {
    JsonObject fields = new JsonObject();
    for (String key : List.of("title", "description", "domain", "priority", "targetDate",
        "weeklyHours", "status", "planId", "reason", "targetMetrics", "milestones")) {
      if (source.has(key) && !source.get(key).isJsonNull()) fields.add(key, source.get(key).deepCopy());
    }
    return fields;
  }

  /** 联网调研目标领域，失败时返回空数组不阻塞流程。 */
  private JsonArray researchSources(String requestText, AgentContext context) {
    JsonArray sources = new JsonArray();
    if (tools == null) return sources;
    try {
      JsonObject arguments = new JsonObject();
      arguments.addProperty("query", requestText + " 学习方法 备考资料 课程阶段 最新");
      AgentResult research = tools.execute(new ToolCall(context.runId() + ":learning:research", null,
          LearningResearchTool.NAME, arguments), context);
      if (research.data().has("sources") && research.data().get("sources").isJsonArray()) {
        sources = research.data().getAsJsonArray("sources");
      }
    } catch (Exception error) {
      LOG.warn("[学习规划] 调研失败，按无资料继续：{}", error.getMessage());
    }
    return sources;
  }

  /** 让模型基于请求与调研资料生成紧凑课程大纲（目标 + 量化指标 + 里程碑 + 阶段主题）。
   *  只输出结构与主题，输出量小；逐日任务由 {@link #expandDailyChunk} 按块生成。 */
  private JsonObject requestCurriculum(String requestText, JsonObject input, JsonArray sources,
                                       AgentContext context) throws Exception {
    JsonObject modelContext = new JsonObject();
    modelContext.addProperty("request", requestText);
    modelContext.addProperty("currentDate", LocalDate.now().toString());
    if (input.has("targetDate") && !input.get("targetDate").isJsonNull()) {
      modelContext.addProperty("targetDate", input.get("targetDate").getAsString());
    }
    modelContext.add("sources", sources);
    JsonArray existing = new JsonArray();
    for (var goal : service.listGoals(context.identity())) {
      JsonObject g = new JsonObject();
      g.addProperty("title", goal.title());
      g.addProperty("domain", goal.domain());
      g.addProperty("status", goal.status());
      existing.add(g);
    }
    modelContext.add("existingGoals", existing);
    JsonArray messages = LearningPrompt.curriculumMessages(modelContext);
    appendSharedContext(messages, context);
    // 紧凑大纲输出 ~1500-2500 token（约 40-60 秒，SiliconFlow 高峰可能更久），
    // 单次超时 150s 给慢响应留余量，避免超时重试挤占逐日分块展开的预算。
    return model.completeJson("learning-curriculum", messages, 0.3, 2200, 150, 2);
  }

  /** 把紧凑课程大纲展开成 create_learning_plan 的 fields：阶段 → 每日任务 → 每日日程。
   *  逐日任务按至多 30 天一块分块让模型生成；超出展开预算（预留组装与回复时间）的天数
   *  回退 {@link #topicFor} 轮换补齐，保证长周期计划在子代理预算内一定完成。 */
  private JsonObject dailyPlanFields(JsonObject curriculum, JsonObject goal, String title,
                                     LocalDate targetDate, JsonObject input, AgentContext context) {
    JsonObject fields = new JsonObject();
    fields.addProperty("title", string(curriculum, "planTitle", title + "学习计划"));
    fields.addProperty("description", "由学习规划 Agent 联网调研后生成的每日学习计划，每天一个任务按计划推进。");
    fields.addProperty("color", "#72806A");
    fields.addProperty("dueDate", targetDate.toString());
    fields.addProperty("reason", "learning_agent");
    // 内嵌学习目标（含量化指标与里程碑），落库时关联 plan_id
    JsonObject goalFields = goal.deepCopy();
    if (curriculum.has("targetMetrics")) goalFields.add("targetMetrics", curriculum.get("targetMetrics").deepCopy());
    if (curriculum.has("milestones")) goalFields.add("milestones", curriculum.get("milestones").deepCopy());
    if (input.has("weeklyHours") && input.get("weeklyHours").isJsonPrimitive()) {
      goalFields.addProperty("weeklyHours", input.get("weeklyHours").getAsDouble());
    }
    if (input.has("domain") && input.get("domain").isJsonPrimitive()) {
      goalFields.addProperty("domain", input.get("domain").getAsString());
    }
    fields.add("learningGoal", goalFields);

    int totalDays = (int) java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), targetDate) + 1;
    // 每日学习时段：默认 20:00，但若与用户已有日程（如其他计划的每日学习）冲突则自动顺延到空闲时段，
    // 否则确认时会因 schedule_conflict 整体回滚。探测以今天为基准；学习计划通常每天同一时段推进。
    LocalTime dailySlot = pickFreeDailySlot(context, LocalDate.now(), 120);
    JsonArray modelStages = array(curriculum, "stages");
    if (modelStages.isEmpty()) {
      JsonObject fallback = new JsonObject();
      fallback.addProperty("title", "系统学习");
      fallback.addProperty("days", totalDays);
      fallback.addProperty("dailyMinutes", 90);
      JsonArray topics = new JsonArray(); topics.add("当日学习内容");
      fallback.add("topics", topics);
      fallback.addProperty("dailyTemplate", "完成{topic}并记录学习结果");
      modelStages.add(fallback);
    }

    // 第一阶段：纯算术收集各阶段元数据（不触模型），确定阶段边界与全局每日序号，
    // 供并发展开与按序组装共用，保证阶段划分与原始串行逻辑完全一致。
    List<StageWork> works = new ArrayList<>();
    LocalDate cursor = LocalDate.now();
    int cursorDay = 1;
    int allocated = 0;
    for (int s = 0; s < modelStages.size() && !cursor.isAfter(targetDate); s++) {
      JsonObject ms = modelStages.get(s).getAsJsonObject();
      int stageDays = s == modelStages.size() - 1 ? totalDays - allocated : intValue(ms, "days", 0);
      if (stageDays <= 0) stageDays = totalDays - allocated;
      if (stageDays <= 0) break;
      works.add(new StageWork(ms, cursor, cursorDay, stageDays,
          string(ms, "title", "阶段" + (s + 1)),
          Math.max(25, intValue(ms, "dailyMinutes", 90)), array(ms, "topics"),
          string(ms, "dailyTemplate", "完成{topic}并记录学习结果"),
          string(ms, "priority", s == 0 ? "high" : "medium")));
      allocated += stageDays;
      cursor = cursor.plusDays(stageDays);
      cursorDay += stageDays;
    }

    // 第二阶段：各阶段逐日展开并发执行（阶段之间互不依赖），重叠模型等待时间。
    // ModelClient 线程安全（每次调用独立 HttpClient 请求）、AgentContext 不可变、
    // deadline 由各线程共享 ⇒ 总墙钟仍被子代理预算封顶，并行不会放大耗时不封顶。
    JsonArray[] expandedPlans = new JsonArray[works.size()];
    List<Thread> expansionThreads = new ArrayList<>();
    for (int i = 0; i < works.size(); i++) {
      StageWork w = works.get(i);
      final int index = i;
      Thread thread = Thread.ofVirtual().start(() -> {
        JsonArray chunks = expandStageDaily(w.modelStage, w.title, w.start, w.days, context);
        expandedPlans[index] = buildStageDailyPlan(chunks, w.startDay, w.days, w.topics, w.title, w.template);
      });
      expansionThreads.add(thread);
    }
    for (Thread thread : expansionThreads) {
      try {
        thread.join();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        // 被中断（如外层预算取消）时不再等待剩余线程：已完成的展开照常组装，
        // 未完成的 expandedPlans[i] 为 null，装配时回退主题轮换补齐。
        break;
      }
    }

    // 第三阶段：按序组装阶段 → 每日任务 → 每日日程（纯 Java 计算，快）。
    JsonArray stages = new JsonArray();
    LocalDate date = LocalDate.now();
    int dayIndex = 1;
    JsonArray lastDailyPlan = new JsonArray();
    for (int i = 0; i < works.size(); i++) {
      StageWork w = works.get(i);
      JsonArray dailyPlan = expandedPlans[i] == null ? new JsonArray() : expandedPlans[i];
      lastDailyPlan = dailyPlan;
      date = w.start;
      dayIndex = w.startDay;
      JsonObject stage = new JsonObject();
      stage.addProperty("title", w.title);
      stage.addProperty("dueDate", w.start.plusDays(w.days - 1L).toString());
      JsonArray tasks = new JsonArray();
      for (int d = 0; d < w.days && !date.isAfter(targetDate); d++, date = date.plusDays(1), dayIndex++) {
        JsonObject planItem = dailyPlanItem(dailyPlan, d);
        String dayTitle = planItem == null ? "" : string(planItem, "title", "");
        String dayContent = planItem == null ? "" : string(planItem, "content", "");
        if (dayTitle.isBlank()) {
          // 没有逐日安排时退回轮换主题，保证每天仍有具体内容而非泛化阶段名。
          String topic = topicFor(dayIndex, w.topics);
          dayTitle = "当日学习内容".equals(topic) ? w.title : topic;
        }
        if (dayContent.isBlank()) {
          dayContent = w.template.replace("{topic}", dayTitle);
        }
        if (dayTitle.length() > 50) dayTitle = dayTitle.substring(0, 50) + "…";
        JsonObject task = new JsonObject();
        task.addProperty("title", "Day " + dayIndex + " · " + dayTitle);
        task.addProperty("description", dayContent);
        task.addProperty("priority", w.priority);
        task.addProperty("estimatedMinutes", w.minutes);
        task.addProperty("dueAt", date + "T23:00:00");
        JsonArray schedules = new JsonArray();
        JsonObject schedule = new JsonObject();
        schedule.addProperty("title", w.title + " · 每日学习");
        schedule.addProperty("startAt", date + "T" + dailySlot);
        schedule.addProperty("durationMinutes", w.minutes);
        schedules.add(schedule);
        task.add("schedules", schedules);
        tasks.add(task);
      }
      stage.add("tasks", tasks);
      stages.add(stage);
    }
    // 兜底：阶段覆盖不足时补齐到目标日期，尽量复用最后一个阶段的逐日安排，避免泛化成"冲刺巩固"。
    while (!date.isAfter(targetDate)) {
      JsonObject stage = new JsonObject();
      stage.addProperty("title", "冲刺巩固");
      stage.addProperty("dueDate", targetDate.toString());
      JsonArray tasks = new JsonArray();
      while (!date.isAfter(targetDate)) {
        JsonObject planItem = dailyPlanItem(lastDailyPlan, dayIndex - 1);
        String dayTitle = planItem == null ? "" : string(planItem, "title", "");
        String dayContent = planItem == null ? "" : string(planItem, "content", "");
        if (dayTitle.isBlank()) dayTitle = "冲刺巩固";
        if (dayContent.isBlank()) dayContent = "按计划完成当日学习并复盘";
        if (dayTitle.length() > 50) dayTitle = dayTitle.substring(0, 50) + "…";
        JsonObject task = new JsonObject();
        task.addProperty("title", "Day " + dayIndex + " · " + dayTitle);
        task.addProperty("description", dayContent);
        task.addProperty("priority", "medium");
        task.addProperty("estimatedMinutes", 90);
        task.addProperty("dueAt", date + "T23:00:00");
        JsonArray schedules = new JsonArray();
        JsonObject schedule = new JsonObject();
        schedule.addProperty("title", "冲刺巩固 · 每日学习");
        schedule.addProperty("startAt", date + "T" + dailySlot);
        schedule.addProperty("durationMinutes", 90);
        schedules.add(schedule);
        task.add("schedules", schedules);
        tasks.add(task);
        date = date.plusDays(1); dayIndex++;
      }
      stage.add("tasks", tasks);
      stages.add(stage);
    }
    fields.add("stages", stages);
    return fields;
  }

  /**
   * 为每日学习日程挑选一个空闲开始时段。默认 20:00；若与用户已有日程（如其他计划每晚的学习日程）
   * 冲突则依次顺延到 19:00 / 21:00 / 18:00 / 22:00 / 17:00 / 23:00 / 16:00，保证确认时不会因
   * schedule_conflict 整体回滚。commands 为 null（纯解析场景）时直接用默认时段。
   */
  private LocalTime pickFreeDailySlot(AgentContext context, LocalDate day, int durationMinutes) {
    if (commands == null) return LocalTime.of(20, 0);
    LocalTime[] candidates = {
        LocalTime.of(20, 0), LocalTime.of(19, 0), LocalTime.of(21, 0),
        LocalTime.of(18, 0), LocalTime.of(22, 0), LocalTime.of(17, 0),
        LocalTime.of(23, 0), LocalTime.of(16, 0)
    };
    try {
      for (LocalTime slot : candidates) {
        String startAt = day.atTime(slot).toString();
        if (commands.scheduleConflicts(context.identity(), startAt, durationMinutes).isEmpty()) {
          return slot;
        }
      }
    } catch (Exception error) {
      LOG.warn("[学习规划] 每日时段探测失败，退回默认 20:00：{}", error.getMessage());
      return LocalTime.of(20, 0);
    }
    // 全部时段都被占用（极端情况），仍返回默认并让确认时的冲突校验提示用户调整。
    return LocalTime.of(20, 0);
  }

  /** 单个学习阶段的展开元数据：先纯算术收集，再并发展开，最后按序组装。 */
  private record StageWork(JsonObject modelStage, LocalDate start, int startDay, int days,
                           String title, int minutes, JsonArray topics, String template, String priority) {}

  /** 用模型展开结果（可能短于 days）拼出与阶段天数等长的逐日计划，缺的天数按主题轮换补齐。
   *  返回的数组与阶段天数严格等长，后续逐日循环可直接取用。 */
  private JsonArray buildStageDailyPlan(JsonArray expanded, int startDay, int days, JsonArray topics,
                                        String stageTitle, String template) {
    if (expanded == null) expanded = new JsonArray();
    JsonArray dailyPlan = new JsonArray();
    for (int d = 0; d < days; d++) {
      JsonObject planItem = d < expanded.size() ? dailyPlanItem(expanded, d) : null;
      if (planItem == null) {
        String topic = topicFor(startDay + d, topics);
        String fallbackTitle = "当日学习内容".equals(topic) ? stageTitle : topic;
        planItem = new JsonObject();
        planItem.addProperty("title", fallbackTitle);
        planItem.addProperty("content", template.replace("{topic}", fallbackTitle));
      }
      dailyPlan.add(planItem);
    }
    return dailyPlan;
  }

  /** 分块展开某个阶段的逐日任务：每块至多 30 天一次模型调用；超出预算立即停止，剩余天数回退轮换。
   *  返回的数组元素为 {title,content}，可能短于 stageDays（未覆盖部分由调用方轮换补齐）。 */
  private JsonArray expandStageDaily(JsonObject stage, String stageTitle, LocalDate start,
                                     int stageDays, AgentContext context) {
    JsonArray result = new JsonArray();
    if (stageDays <= 0) return result;
    // 留 45 秒给组装与草案回复：展开只用子代理预算扣掉余量后的时间。
    Instant expansionDeadline = context.deadline().minusSeconds(45);
    LocalDate cursor = start;
    int remaining = stageDays;
    while (remaining > 0) {
      if (Instant.now().isAfter(expansionDeadline)) break;
      int days = Math.min(30, remaining);
      JsonArray chunk = expandDailyChunk(stage, stageTitle, cursor, days);
      // 块内容不足（模型返回条目少或失败）说明输出不稳定，停止展开以免逐日内容越写越空。
      if (chunk == null || chunk.size() != days) break;
      for (JsonElement element : chunk) result.add(element);
      cursor = cursor.plusDays(days);
      remaining -= days;
    }
    return result;
  }

  /** 让模型为一个日期块生成逐日任务；失败或条目不符时返回 null，由调用方回退轮换。 */
  private JsonArray expandDailyChunk(JsonObject stage, String stageTitle, LocalDate start, int days) {
    if (!model.configured()) return null;
    try {
      JsonObject modelContext = new JsonObject();
      modelContext.addProperty("stageTitle", stageTitle);
      modelContext.addProperty("startDate", start.toString());
      modelContext.addProperty("days", days);
      if (stage.has("focus") && !stage.get("focus").isJsonNull()
          && !stage.get("focus").getAsString().isBlank()) {
        modelContext.addProperty("focus", stage.get("focus").getAsString());
      }
      modelContext.add("topics", array(stage, "topics"));
      if (stage.has("dailyTemplate") && !stage.get("dailyTemplate").isJsonNull()
          && !stage.get("dailyTemplate").getAsString().isBlank()) {
        modelContext.addProperty("dailyTemplate", stage.get("dailyTemplate").getAsString());
      }
      JsonArray messages = LearningPrompt.dailyChunkMessages(modelContext);
      // 每天约 100 token（title+content+JSON 开销），30 天需 ~3300 token：max_tokens 必须给足，
      // 否则模型输出被截断成非法 JSON，整块回退轮换（实测 900 时大部分块失败）。
      // 单次超时 150s：SiliconFlow 高峰生成 3500 token 可能超 100s，超时重试会浪费整个子代理预算。
      JsonObject output = model.completeJson("learning-daily-chunk", messages, 0.4, 3500, 150, 2);
      JsonArray chunk = array(output, "days");
      if (chunk.size() > days) {
        JsonArray trimmed = new JsonArray();
        for (int i = 0; i < days; i++) trimmed.add(chunk.get(i));
        chunk = trimmed;
      }
      return chunk;
    } catch (Exception error) {
      LOG.warn("[学习规划] 每日任务分块生成失败，回退轮换：{}", error.getMessage());
      return null;
    }
  }

  /** 取 dailyPlan 的第 index 条（超出条数时按天轮换复用）；支持 {title,content} 对象或纯字符串，无效时返回 null。 */
  private JsonObject dailyPlanItem(JsonArray dailyPlan, int index) {
    if (dailyPlan == null || dailyPlan.isEmpty()) return null;
    JsonElement element = dailyPlan.get(index % dailyPlan.size());
    if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
      String text = element.getAsString();
      if (text.isBlank()) return null;
      JsonObject item = new JsonObject();
      item.addProperty("title", "");
      item.addProperty("content", text);
      return item;
    }
    if (!element.isJsonObject()) return null;
    JsonObject item = element.getAsJsonObject();
    boolean hasTitle = item.has("title") && !item.get("title").isJsonNull()
        && !item.get("title").getAsString().isBlank();
    boolean hasContent = item.has("content") && !item.get("content").isJsonNull()
        && !item.get("content").getAsString().isBlank();
    return (hasTitle || hasContent) ? item : null;
  }

  private String topicFor(int dayIndex, JsonArray topics) {
    if (topics == null || topics.isEmpty()) return "当日学习内容";
    JsonElement element = topics.get((dayIndex - 1) % topics.size());
    return element.isJsonPrimitive() ? element.getAsString() : "当日学习内容";
  }

  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray() ? object.get(name).getAsJsonArray() : new JsonArray();
  }

  private int intValue(JsonObject object, String name, int fallback) {
    return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsInt() : fallback;
  }

  private LocalDate parseDate(JsonObject object, String name) {
    String value = string(object, name, "");
    if (value.isBlank()) return null;
    try { return LocalDate.parse(value); } catch (java.time.format.DateTimeParseException ignored) { return null; }
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
