package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.ToolDefinition;
import com.changlu.planner.agent.core.ToolRegistry;

/**
 * 学习规划 Subagent 的专属 Tool 注册表。
 * 只读工具直接执行；写操作工具 requiresConfirmation=true，
 * 必须先生成待确认草案。
 */
public final class LearningTools {
  private LearningTools() {}

  /** 学习规划领域的 Tool 名称常量。 */
  public static final String ANALYZE_PROGRESS = "learning.analyze_progress";
  public static final String SUGGEST_STUDY_PLAN = "learning.suggest_study_plan";
  public static final String DETECT_KNOWLEDGE_GAPS = "learning.detect_knowledge_gaps";
  public static final String CREATE_GOAL = "learning.create_goal";
  public static final String UPDATE_GOAL = "learning.update_goal";
  public static final String DELETE_GOAL = "learning.delete_goal";
  public static final String VIEW_STATS = "learning.view_stats";

  /**
   * 构建学习规划专属 ToolRegistry。
   * 3 个只读分析工具 + 3 个需确认的写工具 + 1 个统计查询。
   */
  public static ToolRegistry registry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ToolDefinition(ANALYZE_PROGRESS,
        "分析所有学习目标的进度、趋势和完成率，返回结构化进度报告",
        "subagent", false));
    registry.register(new ToolDefinition(SUGGEST_STUDY_PLAN,
        "根据学习目标、可用时间和当前进度生成周学习计划建议",
        "subagent", false));
    registry.register(new ToolDefinition(DETECT_KNOWLEDGE_GAPS,
        "检测知识薄弱点和被忽略的领域，给出平衡建议",
        "subagent", false));
    registry.register(new ToolDefinition(VIEW_STATS,
        "查看学习统计摘要：活跃目标数、累计时长、连续天数、专注度平均分等",
        "subagent", false));
    registry.register(new ToolDefinition(CREATE_GOAL,
        "创建新的学习目标——先生成待确认草案，用户确认后执行",
        "subagent", true));
    registry.register(new ToolDefinition(UPDATE_GOAL,
        "更新学习目标的标题、领域、优先级、目标日期或每周时长——先生成草案",
        "subagent", true));
    registry.register(new ToolDefinition(DELETE_GOAL,
        "删除学习目标——先生成待确认草案，用户确认后软删除",
        "subagent", true));
    return registry;
  }
}
