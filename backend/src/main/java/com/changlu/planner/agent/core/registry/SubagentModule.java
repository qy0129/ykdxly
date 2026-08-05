package com.changlu.planner.agent.core.registry;

import com.changlu.planner.agent.core.tool.ToolRegistry;

public interface SubagentModule {
  void register(SubagentRegistry subagents, ToolRegistry tools);
}
