package com.changlu.planner.agent.tools;

import java.util.List;

/** 现有计划动作名称目录；具体执行统一由新版 planning.assistant Tool 负责。 */
public final class PlanningTools {
  public static final List<String> ACTION_TYPES = List.of(
      "create_plan", "update_plan", "delete_plan", "restore_plan",
      "create_stage", "update_stage", "delete_stage", "restore_stage",
      "create_task", "update_task", "complete_task", "delay_task", "block_task",
      "skip_task", "cancel_task", "delete_task", "restore_task",
      "create_todo", "update_todo", "complete_todo", "delay_todo", "delete_todo", "restore_todo",
      "create_schedule", "update_schedule", "complete_schedule", "delay_schedule", "delete_schedule",
      "restore_schedule", "batch_reschedule", "update_preference",
      "create_learning_goal", "update_learning_goal", "delete_learning_goal", "create_learning_plan");

  private PlanningTools() {}

}
