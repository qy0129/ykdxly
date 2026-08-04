package com.changlu.planner.agent.core.tool;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;

public interface ToolHandler {
  ToolDefinition definition();
  AgentResult execute(ToolCall call, AgentContext context) throws Exception;
}
