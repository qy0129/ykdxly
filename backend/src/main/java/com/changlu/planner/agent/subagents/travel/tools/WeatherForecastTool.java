package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.*;
import com.changlu.planner.agent.core.tool.*;
import com.changlu.planner.agent.subagents.travel.services.WeatherForecastService;

public final class WeatherForecastTool implements ToolHandler {
  public static final String NAME = "travel.weather.forecast";
  private final WeatherForecastService service;
  public WeatherForecastTool(WeatherForecastService service) { this.service = service; }
  @Override public ToolDefinition definition() { return LocationContextTool.definition(NAME, "获取逐日天气预报", 20); }
  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    return AgentResult.completed("已获取目的地天气。", service.forecast(call.arguments(), context.traceId()), context.traceId());
  }
}
