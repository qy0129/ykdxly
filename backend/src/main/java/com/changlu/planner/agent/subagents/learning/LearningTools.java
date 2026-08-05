package com.changlu.planner.agent.subagents.learning;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

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
  public static Map<String, ToolDefinition> definitions() {
    Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    add(definitions, ANALYZE_PROGRESS, "分析所有学习目标的进度、趋势和完成率，返回结构化进度报告", false);
    add(definitions, SUGGEST_STUDY_PLAN, "根据学习目标、可用时间和当前进度生成周学习计划建议", false);
    add(definitions, DETECT_KNOWLEDGE_GAPS, "检测知识薄弱点和被忽略的领域，给出平衡建议", false);
    add(definitions, VIEW_STATS, "查看学习统计摘要：活跃目标数、累计时长、连续天数、专注度平均分等", false);
    add(definitions, CREATE_GOAL, "创建新的学习目标，先生成待确认草案", true);
    add(definitions, UPDATE_GOAL, "更新学习目标，先生成待确认草案", true);
    add(definitions, DELETE_GOAL, "删除学习目标，先生成待确认草案", true);
    return definitions;
  }

  public static ToolHandler handler(String name) {
    ToolDefinition definition = definitions().get(name);
    if (definition == null) throw new IllegalArgumentException("学习工具未注册：" + name);
    return new ToolHandler() {
      @Override public ToolDefinition definition() { return definition; }
      @Override public AgentResult execute(ToolCall call, AgentContext context) {
        return AgentResult.failed("LEARNING_TOOL_DELEGATED", "学习工具由 learning Subagent 统一编排", false,
            context.traceId());
      }
    };
  }

  private static void add(Map<String, ToolDefinition> definitions, String name, String description,
                          boolean requiresConfirmation) {
    definitions.put(name, new ToolDefinition(name, "1.0.0", description,
        new JsonObject(), new JsonObject(), java.util.Set.of(),
        requiresConfirmation ? ToolRiskLevel.LOW_RISK_WRITE : ToolRiskLevel.READ_ONLY,
        requiresConfirmation ? ToolSideEffect.INTERNAL_WRITE : ToolSideEffect.NONE,
        requiresConfirmation, Duration.ofSeconds(60), RetryPolicy.none()));
  }
}
