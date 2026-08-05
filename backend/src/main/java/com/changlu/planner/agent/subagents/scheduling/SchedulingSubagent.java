package com.changlu.planner.agent.subagents.scheduling;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 只读分析排期问题，写操作仍由主 Agent 的规划 Tool 生成草案。 */
public final class SchedulingSubagent implements Subagent {
  private final ConflictTool conflicts;
  private final SubagentDefinition definition = new SubagentDefinition(
      "scheduling", "1.0.0", "检查未来日程冲突、可用时段和单次安排时长",
      List.of("日程冲突", "检查日程", "排期问题", "可用时段"), List.of("直接修改日程"),
      new JsonObject(), new JsonObject(), Set.of(), false, false, Duration.ofSeconds(60), 1);

  public SchedulingSubagent(ConflictTool conflicts) { this.conflicts = conflicts; }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    SchedulingResult result = conflicts.inspect(context.identity());
    return AgentResult.completed(result.message(), result.toJson(), context.traceId());
  }
}
