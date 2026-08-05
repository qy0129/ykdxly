package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习计划建议工具。
 * 只读工具，基于用户的学习目标、可用时段和历史完成情况，
 * 通过 AI 模型生成可执行的周学习计划建议。
 */
public final class StudyPlanSuggestionTool {
  private static final Logger LOG = LoggerFactory.getLogger(StudyPlanSuggestionTool.class);
  private final LearningService service;
  private final ModelClient model;

  public StudyPlanSuggestionTool(LearningService service, ModelClient model) {
    this.service = service;
    this.model = model;
  }

  /**
   * 生成学习计划建议。
   * 需要模型支持，如果模型未配置则返回基于规则的简单建议。
   */
  public JsonObject suggest(Database.Context context) throws Exception {
    long startedAt = System.currentTimeMillis();
    LOG.info("[学习计划建议] 开始 用户={} 工作区={}", context.userId(), context.workspaceId());

    try {
      List<LearningService.LearningGoal> goals = service.listGoals(context);
      List<LearningService.LearningSession> sessions = service.listSessions(context, 14);
      LearningService.LearningStats stats = service.stats(context);

      // 构建上下文供模型分析
      JsonObject modelContext = new JsonObject();
      modelContext.addProperty("activeGoals", goals.stream()
          .filter(g -> "active".equals(g.status())).count());
      modelContext.addProperty("totalGoals", goals.size());

      JsonArray goalsJson = new JsonArray();
      for (LearningService.LearningGoal goal : goals) {
        if (!"active".equals(goal.status()) && !"paused".equals(goal.status())) continue;
        JsonObject g = goal.toJson();
        goalsJson.add(g);
      }
      modelContext.add("goals", goalsJson);
      modelContext.add("stats", stats.toJson());

      // 如果有模型，使用 AI 生成建议
      if (model.configured()) {
        try {
          JsonObject aiResult = model.completeJson("study-plan-suggestion",
              LearningPrompt.suggestionMessages(modelContext), 0.3, 1500, 25, 1);
          long durationMs = System.currentTimeMillis() - startedAt;
          LOG.info("[学习计划建议] AI生成完成 耗时={}毫秒", durationMs);
          JsonObject data = new JsonObject();
          data.addProperty("generatedAt", java.time.LocalDateTime.now().toString());
          data.addProperty("source", "ai");
          data.add("suggestion", aiResult);
          data.add("goals", goalsJson);
          data.add("stats", stats.toJson());
          return data;
        } catch (Exception aiError) {
          LOG.warn("[学习计划建议] AI生成失败，回退规则建议 原因={}", aiError.getMessage());
        }
      }

      // 回退：基于规则的建议
      JsonObject ruleBased = buildRuleBasedSuggestion(goals, stats);
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.info("[学习计划建议] 规则生成完成 耗时={}毫秒", durationMs);
      return ruleBased;
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.error("[学习计划建议] 失败 耗时={}毫秒 原因={}", durationMs, e.getMessage(), e);
      throw e;
    }
  }

  private JsonObject buildRuleBasedSuggestion(
      List<LearningService.LearningGoal> goals, LearningService.LearningStats stats) {
    JsonObject data = new JsonObject();
    data.addProperty("generatedAt", java.time.LocalDateTime.now().toString());
    data.addProperty("source", "rule");

    JsonObject suggestion = new JsonObject();
    JsonArray priorityOrder = new JsonArray();
    int totalWeeklyMinutes = 0;

    for (LearningService.LearningGoal goal : goals) {
      if (!"active".equals(goal.status())) continue;
      priorityOrder.add(goal.title());
      if (goal.weeklyHours() != null) {
        totalWeeklyMinutes += (int) (goal.weeklyHours() * 60);
      }
    }
    suggestion.add("priorityOrder", priorityOrder);
    suggestion.addProperty("recommendedWeeklyMinutes",
        Math.max(totalWeeklyMinutes, 180)); // 至少建议每周3小时
    suggestion.addProperty("note", totalWeeklyMinutes == 0
        ? "尚未设置每周学习时长，建议为每个目标设定具体的每周小时数。"
        : "按当前目标计算，每周需要约 " + (totalWeeklyMinutes / 60) + " 小时。");

    JsonArray adjustments = new JsonArray();
    if (stats.weeklyMinutes() < totalWeeklyMinutes && totalWeeklyMinutes > 0) {
      adjustments.add("当前周学习时长 (" + stats.weeklyMinutes() + " 分钟) 低于目标 ("
          + totalWeeklyMinutes + " 分钟)，建议适当增加学习时间");
    }
    if (stats.currentStreak() == 0) {
      adjustments.add("连续学习天数为 0，建议今天开始建立学习习惯");
    } else if (stats.currentStreak() < 3) {
      adjustments.add("连续学习 " + stats.currentStreak() + " 天，继续保持以巩固习惯");
    }
    suggestion.add("adjustments", adjustments);
    suggestion.add("weeklyPlan", new JsonArray());  // 规则模式不生成详细周计划

    data.add("suggestion", suggestion);

    JsonArray goalsJson = new JsonArray();
    for (LearningService.LearningGoal goal : goals) {
      goalsJson.add(goal.toJson());
    }
    data.add("goals", goalsJson);
    data.add("stats", stats.toJson());
    return data;
  }
}
