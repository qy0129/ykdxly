package com.changlu.planner.features.plan;

import java.util.List;

/** 计划任务状态机；HTTP、AI 和微信最终都必须经过同一规则。 */
final class TaskStateRules {
  static final List<String> STATUSES = List.of("pending", "in_progress", "done", "blocked", "skipped", "cancelled");

  private TaskStateRules() {}

  static void validate(String current, String next, String action, String reason, String dueAt) {
    if (next == null) return;
    if (!STATUSES.contains(next)) throw new IllegalArgumentException("invalid_task_status");
    if ("blocked".equals(next) && (reason == null || reason.isBlank())) throw new IllegalArgumentException("blocked_reason_required");
    if ("delay_task".equals(action) && (dueAt == null || dueAt.isBlank())) throw new IllegalArgumentException("dueAt_required");
    if (List.of("done", "skipped", "cancelled").contains(current)
        && !current.equals(next) && !"pending".equals(next)) {
      throw new IllegalArgumentException("terminal_task_must_reopen_first");
    }
  }
}
