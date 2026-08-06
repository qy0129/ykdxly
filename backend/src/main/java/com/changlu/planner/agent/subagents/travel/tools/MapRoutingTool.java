package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.*;
import com.changlu.planner.agent.core.tool.*;
import com.changlu.planner.agent.subagents.travel.services.MapRoutingService;

public final class MapRoutingTool implements ToolHandler {
  public static final String NAME = "travel.map.routing";
  private final MapRoutingService service;
  public MapRoutingTool(MapRoutingService service) { this.service = service; }
  @Override public ToolDefinition definition() { return LocationContextTool.definition(NAME, "计算已选活动之间的必要本地路线", 25); }
  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    return AgentResult.completed("已计算活动路线。", service.route(call.arguments(), context.traceId()), context.traceId());
  }
}
