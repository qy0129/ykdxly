package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.*;
import com.changlu.planner.agent.core.tool.*;
import com.changlu.planner.agent.subagents.travel.services.AttractionResearchService;

public final class AttractionResearchTool implements ToolHandler {
  public static final String NAME = "travel.attraction.research";
  private final AttractionResearchService service;
  public AttractionResearchTool(AttractionResearchService service) { this.service = service; }
  @Override public ToolDefinition definition() { return LocationContextTool.definition(NAME, "获取带来源的候选景点", 25); }
  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    return AgentResult.completed("已获取候选景点。", service.research(call.arguments(), context.traceId()), context.traceId());
  }
}
