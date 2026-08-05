package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.Subagent;
import com.changlu.planner.features.learning.LearningService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
  private final Gson gson = new Gson();

  public LearningSubagent(LearningService service, ModelClient model) {
    this.service = service;
    this.model = model;
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
  public JsonObject execute(String request, AgentContext context) throws Exception {
    long startedAt = System.currentTimeMillis();
    String intent = classifyIntent(request);
    LOG.info("[学习规划Subagent] 执行开始 意图={} 用户={} 工作区={} 请求预览={}",
        intent, context.identity().userId(), context.identity().workspaceId(),
        request.length() > 100 ? request.substring(0, 100) + "..." : request);

    try {
      LearningResult result = switch (intent) {
        case "analyze_progress" -> handleAnalyzeProgress(context);
        case "suggest_plan" -> handleSuggestPlan(context);
        case "detect_gaps" -> handleDetectGaps(context);
        case "view_stats" -> handleViewStats(context);
        case "create_goal" -> handleCreateGoal(request, context);
        case "update_goal" -> handleUpdateGoal(request, context);
        case "delete_goal" -> handleDeleteGoal(request, context);
        default -> handleGeneral(request, context);
      };

      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.info("[学习规划Subagent] 执行完成 意图={} 状态={} 耗时={}毫秒",
          intent, result.status(), durationMs);
      return result.toAgentResponse();
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.error("[学习规划Subagent] 执行失败 意图={} 耗时={}毫秒 原因={}",
          intent, durationMs, e.getMessage(), e);
      return LearningResult.error("学习规划处理失败：" + e.getMessage(), e.getMessage())
          .toAgentResponse();
    }
  }

  // ==================== 意图分类 ====================

  /**
   * 基于关键词分类用户意图。
   * 这是 Subagent 内部的轻量路由，不涉及主 Agent 的 if/else。
   */
  private String classifyIntent(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    if (normalized.contains("进度") || normalized.contains("进展") || normalized.contains("分析")) {
      return "analyze_progress";
    }
    if (normalized.contains("建议") || normalized.contains("计划") || normalized.contains("安排学习")
        || normalized.contains("怎么学") || normalized.contains("学习方案")) {
      return "suggest_plan";
    }
    if (normalized.contains("缺口") || normalized.contains("薄弱") || normalized.contains("知识体系")
        || normalized.contains("领域") || normalized.contains("检测")) {
      return "detect_gaps";
    }
    if (normalized.contains("统计") || normalized.contains("数据") || normalized.contains("总结")) {
      return "view_stats";
    }
    if (normalized.contains("创建") || normalized.contains("新建") || normalized.contains("添加目标")
        || normalized.contains("设立")) {
      return "create_goal";
    }
    if (normalized.contains("修改") || normalized.contains("更新") || normalized.contains("调整目标")) {
      return "update_goal";
    }
    if (normalized.contains("删除") || normalized.contains("移除") || normalized.contains("放弃")) {
      return "delete_goal";
    }
    return "general";
  }

  // ==================== 处理函数 ====================

  private LearningResult handleAnalyzeProgress(AgentContext context) throws Exception {
    JsonObject data = progressTool.analyze(context.identity());
    String message = buildProgressMessage(data);
    return LearningResult.success(message, data);
  }

  private LearningResult handleSuggestPlan(AgentContext context) throws Exception {
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
   * 对参数做校验，检查重复，不直接写入数据库。
   */
  private LearningResult handleCreateGoal(String request, AgentContext context) throws Exception {
    // 参数校验
    JsonArray validationErrors = new JsonArray();
    if (request == null || request.isBlank()) {
      validationErrors.add(errorItem("request", "请求内容不能为空"));
      return LearningResult.validationError("参数校验失败", validationErrors);
    }

    // 加载现有目标和领域，避免重复创建
    var goals = service.listGoals(context.identity());
    var areas = service.listKnowledgeAreas(context.identity());

    JsonObject modelContext = new JsonObject();
    modelContext.addProperty("request", request);
    JsonArray existing = new JsonArray();
    for (var goal : goals) {
      JsonObject g = new JsonObject();
      g.addProperty("title", goal.title());
      g.addProperty("domain", goal.domain());
      g.addProperty("status", goal.status());
      existing.add(g);
    }
    modelContext.add("existingGoals", existing);
    JsonArray knownAreas = new JsonArray();
    for (var area : areas) {
      knownAreas.add(area.name());
    }
    modelContext.add("knownAreas", knownAreas);

    // 使用模型生成草案
    if (!model.configured()) {
      return LearningResult.error("AI 模型未配置，无法生成学习目标草案",
          "请配置 PLANNER_AI_API_KEY 后重试");
    }

    try {
      JsonObject aiResult = model.completeJson("learning-goal-draft",
          LearningPrompt.goalDraftMessages(modelContext), 0.3, 1200, 25, 1);

      JsonObject data = new JsonObject();
      data.addProperty("generatedAt", java.time.LocalDateTime.now().toString());
      data.addProperty("requiresConfirmation", true);
      data.addProperty("confirmationType", "create_learning_goal");
      data.add("draft", aiResult.has("draft") ? aiResult.get("draft") : aiResult);

      if (aiResult.has("conflicts")) {
        data.add("conflicts", aiResult.get("conflicts"));
      }
      if (aiResult.has("rationale")) {
        data.addProperty("rationale", aiResult.get("rationale").getAsString());
      }

      return LearningResult.pendingConfirmation(
          "已生成学习目标草案：「"
              + (aiResult.has("draft")
                  ? aiResult.getAsJsonObject("draft").get("title").getAsString()
                  : "未命名")
              + "」，请确认后执行。",
          data);
    } catch (Exception e) {
      LOG.warn("[学习规划] AI草案生成失败 原因={}", e.getMessage());
      return LearningResult.error("生成学习目标草案失败，请重试", e.getMessage());
    }
  }

  private LearningResult handleUpdateGoal(String request, AgentContext context) throws Exception {
    return LearningResult.pendingConfirmation(
        "更新学习目标需要先生成草案。请提供目标 ID 和需要修改的字段。",
        new JsonObject());
  }

  private LearningResult handleDeleteGoal(String request, AgentContext context) throws Exception {
    return LearningResult.pendingConfirmation(
        "删除学习目标是不可逆操作。已为你生成待确认草案，请确认后执行。",
        new JsonObject());
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
      messages.add(ModelClient.message("user", modelContext.toString()));

      JsonObject aiResult = model.completeJson("learning-general", messages, 0.3, 1000, 25, 1);
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
}
