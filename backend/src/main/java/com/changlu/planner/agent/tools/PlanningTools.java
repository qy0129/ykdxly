package com.changlu.planner.agent.tools;

import com.changlu.planner.agent.core.ToolDefinition;
import com.changlu.planner.agent.core.ToolRegistry;
import java.util.List;

/** 现有计划执行动作的 Tool 目录；所有动作都先生成草案。 */
public final class PlanningTools {
  public static final List<String> ACTION_TYPES = List.of(
      "create_plan", "update_plan", "delete_plan", "restore_plan",
      "create_stage", "update_stage", "delete_stage", "restore_stage",
      "create_task", "update_task", "complete_task", "delay_task", "block_task",
      "skip_task", "cancel_task", "delete_task", "restore_task",
      "create_todo", "update_todo", "complete_todo", "delay_todo", "delete_todo", "restore_todo",
      "create_schedule", "update_schedule", "complete_schedule", "delay_schedule", "delete_schedule",
      "restore_schedule", "batch_reschedule", "update_preference");

  private PlanningTools() {}

  public static ToolRegistry registry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ToolDefinition("planning.assistant",
        "查询规划数据、进行普通对话，或把计划、任务、待办和日程变更生成待确认草案", "core", false));
    for (String action : ACTION_TYPES) {
      registry.register(new ToolDefinition(action, "规划业务动作 " + action, "tool", true));
    }
    return registry;
  }
}
