package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 知识缺口检测工具。
 * 只读工具，分析用户的知识领域掌握情况，
 * 识别需要加强的领域和被忽略的领域。
 */
public final class KnowledgeGapTool {
  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeGapTool.class);
  private final LearningService service;

  public KnowledgeGapTool(LearningService service) {
    this.service = service;
  }

  /**
   * 检测知识缺口。
   * 基于知识领域的掌握程度、学习频率和目标覆盖情况进行分析。
   */
  public JsonObject detect(Database.Context context) throws Exception {
    long startedAt = System.currentTimeMillis();
    LOG.info("[知识缺口检测] 开始 用户={} 工作区={}", context.userId(), context.workspaceId());

    try {
      List<LearningService.KnowledgeArea> areas = service.listKnowledgeAreas(context);
      List<LearningService.LearningGoal> goals = service.listGoals(context);
      List<LearningService.LearningSession> sessions = service.listSessions(context, 60);

      JsonObject data = new JsonObject();
      data.addProperty("detectedAt", java.time.LocalDateTime.now().toString());

      // 知识领域分析
      JsonArray areasJson = new JsonArray();
      for (LearningService.KnowledgeArea area : areas) {
        JsonObject a = area.toJson();

        // 计算该领域的活跃目标数
        long areaGoals = goals.stream()
            .filter(g -> area.name().equalsIgnoreCase(g.domain())
                && "active".equals(g.status()))
            .count();
        a.addProperty("activeGoals", areaGoals);

        // 最近学习时间
        long daysSinceLastStudy = area.lastStudiedAt() == null ? -1 :
            java.time.Duration.between(area.lastStudiedAt(),
                java.time.LocalDateTime.now()).toDays();
        a.addProperty("daysSinceLastStudy", daysSinceLastStudy);

        areasJson.add(a);
      }
      data.add("knowledgeAreas", areasJson);

      // 识别缺口
      JsonArray gaps = new JsonArray();
      for (LearningService.KnowledgeArea area : areas) {
        if (area.masteryLevel() < 30) {
          JsonObject gap = new JsonObject();
          gap.addProperty("area", area.name());
          gap.addProperty("currentLevel", area.masteryLevel());
          gap.addProperty("targetLevel", 60);
          gap.addProperty("reason", "掌握程度偏低（<30%），建议优先加强");
          gaps.add(gap);
        } else if (area.lastStudiedAt() == null ||
            java.time.Duration.between(area.lastStudiedAt(),
                java.time.LocalDateTime.now()).toDays() > 30) {
          JsonObject gap = new JsonObject();
          gap.addProperty("area", area.name());
          gap.addProperty("currentLevel", area.masteryLevel());
          gap.addProperty("targetLevel", Math.min(area.masteryLevel() + 20, 100));
          gap.addProperty("reason", "超过30天未学习，需要复习巩固");
          gaps.add(gap);
        }
      }
      data.add("gaps", gaps);

      // 被忽略的领域：有学习目标但知识领域中没有对应条目
      JsonArray neglectedAreas = new JsonArray();
      for (LearningService.LearningGoal goal : goals) {
        if (!"active".equals(goal.status())) continue;
        boolean covered = areas.stream()
            .anyMatch(a -> a.name().equalsIgnoreCase(goal.domain()));
        if (!covered) {
          neglectedAreas.add(goal.domain());
        }
      }
      data.add("neglectedAreas", neglectedAreas);

      // 过度学习检测：某领域投入远高于其他
      JsonArray overstudied = new JsonArray();
      if (areas.size() >= 2) {
        double avgMastery = areas.stream()
            .mapToInt(LearningService.KnowledgeArea::masteryLevel).average().orElse(0);
        for (LearningService.KnowledgeArea area : areas) {
          if (area.masteryLevel() > avgMastery + 30 && area.masteryLevel() >= 70) {
            overstudied.add(area.name());
          }
        }
      }
      data.add("overstudiedAreas", overstudied);

      // 均衡性评价
      String balancedView;
      if (areas.isEmpty()) {
        balancedView = "尚未配置知识领域，建议先添加需要学习的领域";
      } else if (gaps.isEmpty() && overstudied.isEmpty()) {
        balancedView = "知识结构较为均衡，各项领域进展良好";
      } else if (!gaps.isEmpty()) {
        balancedView = "存在 " + gaps.size() + " 个薄弱领域需要加强";
      } else {
        balancedView = "部分领域已掌握较好，可以适当关注其他领域";
      }
      data.addProperty("balancedView", balancedView);

      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.info("[知识缺口检测] 完成 耗时={}毫秒 领域数={} 缺口数={}", durationMs, areas.size(), gaps.size());
      return data;
    } catch (Exception e) {
      long durationMs = System.currentTimeMillis() - startedAt;
      LOG.error("[知识缺口检测] 失败 耗时={}毫秒 原因={}", durationMs, e.getMessage(), e);
      throw e;
    }
  }
}
