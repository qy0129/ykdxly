package com.changlu.planner.agent.core.registry;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Temporary adapter; new Subagents must implement the standard contract directly. */
public final class LegacySubagentAdapter implements Subagent {
  private final com.changlu.planner.agent.core.Subagent legacy;
  private final SubagentDefinition definition;

  public LegacySubagentAdapter(com.changlu.planner.agent.core.Subagent legacy) {
    this(legacy, List.of(), List.of());
  }

  public LegacySubagentAdapter(com.changlu.planner.agent.core.Subagent legacy,
                               List<String> supportedScenarios,
                               List<String> unsupportedScenarios) {
    this.legacy = legacy;
    this.definition = new SubagentDefinition(legacy.name(), "0.legacy", legacy.description(),
        supportedScenarios, unsupportedScenarios,
        new JsonObject(), new JsonObject(), Set.of(), true, false, Duration.ofSeconds(90), 1);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    com.changlu.planner.agent.core.AgentContext oldContext = new com.changlu.planner.agent.core.AgentContext(
        context.runId(), context.identity(), context.channel(), context.taskState());
    return AgentResult.fromLegacy(legacy.execute(request.message(), oldContext), context.traceId());
  }
}
