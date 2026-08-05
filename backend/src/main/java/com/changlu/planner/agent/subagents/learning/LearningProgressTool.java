package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 学习进度分析工具。
 * 只读工具，汇总所有学习目标的状态、近期会话和统计数据，
 * 参数校验后生成结构化进度报告供 Subagent 编排使用。
 */
public final class LearningProgressTool {
  private static final Logger LOG = LoggerFactory.getLogger(LearningProgressTool.class);
  private final LearningService service;

  public LearningProgressTool(LearningService service) {
    this.service = service;
  }

  /**
   * 分析学习进度。
   * @param context 用户和工作区上下文（自动携带，无需调用方手动传入）
   * @return 结构化进度数据，json格式
   */
  public JsonObject analyze(Database.Context context) throws Exception {
    long startedAt = System.currentTimeMillis();
    LOG.info("[学习进度分析] 开始 用户={} 工作区={}", context.userId(), context.workspaceId());
    try {
      List<LearningService.LearningGoal> goals = service.listGoals(context);
      List<LearningService.LearningSession> sessions = service.listSessions(context, 30);
      LearningService.LearningStats stats = service.stats(context);

      JsonObject data = new JsonObject();
      data.addProperty("analyzedAt", java.time.LocalDateTime.now().toString());

      // 目标摘要
      JsonArray goalsJson = new JsonArray();
      for (LearningService.LearningGoal goal : goals) {
        JsonObject g = goal.toJson();
        // 添加评估
        String assessment = assessGoal(goal);
        g.addProperty("assessment", assessment);
        goalsJson.add(g);
      }
      data.add("goals", goalsJson);

      // 绘画摘要
      JsonArray sessionsJson = new JsonArray();
      for (LearningService.LearningSession session : sessions) {
        sessionsJson.add(session.toJson());
      }
      data.add("recentSessions", sessionsJson);

      // 统计
      data.add("stats", stats.toJson());

      // 计算摘要指标
      data.addProperty("goalCompletionRate", goals.isEmpty() ? 0 :
          Math.round(goals.stream().filter(g -> "completed".equals(g.status())).count() * 100.0 / goals.size()));

      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.info("[学习进度分析] 完成 耗时={}毫秒 目标数={} 会话数={}", durationMs, goals.size(), sessions.size());
      return data;
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.error("[学习进度分析] 失败 耗时={}毫秒 原因={}", durationMs, e.getMessage(), e);
      throw e;
    }
  }

  private String assessGoal(LearningService.LearningGoal goal) {
    if ("completed".equals(goal.status())) return "已完成，做得好！";
    if ("abandoned".equals(goal.status())) return "已放弃";
    if ("paused".equals(goal.status())) return "已暂停，建议评估是否继续";
    if (goal.progress() >= 80) return "接近完成，保持当前节奏";
    if (goal.progress() >= 50) return "进展良好，继续推进";
    if (goal.progress() >= 20) return "稳步推进中";
    if (goal.completedSessions() == 0) return "尚未开始，建议安排首次学习会话";
    return "刚开始，需要更多投入";
  }
}
