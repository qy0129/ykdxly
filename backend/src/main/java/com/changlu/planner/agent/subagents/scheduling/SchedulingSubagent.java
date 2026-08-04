package com.changlu.planner.agent.subagents.scheduling;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.Subagent;
import com.google.gson.JsonObject;

/** 只读分析排期问题，写操作仍由主 Agent 的规划 Tool 生成草案。 */
public final class SchedulingSubagent implements Subagent {
  private final ConflictTool conflicts;

  public SchedulingSubagent(ConflictTool conflicts) { this.conflicts = conflicts; }

  @Override public String name() { return "scheduling"; }
  @Override public String description() { return "检查未来七天日程冲突、可用时段和单次安排时长"; }

  @Override public JsonObject execute(String request, AgentContext context) throws Exception {
    return conflicts.inspect(context.identity()).toJson();
  }
}
