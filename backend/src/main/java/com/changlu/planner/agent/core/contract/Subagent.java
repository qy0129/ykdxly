package com.changlu.planner.agent.core.contract;

public interface Subagent {
  SubagentDefinition definition();
  AgentResult execute(SubagentRequest request, AgentContext context) throws Exception;

  default String name() { return definition().name(); }

  default String description() { return definition().description(); }
}
