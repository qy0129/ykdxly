package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.registry.SubagentModule;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.agent.subagents.travel.tools.DestinationResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.OpeningHoursTool;
import com.changlu.planner.agent.subagents.travel.tools.RouteEstimateTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelDraftTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelPlanValidationTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelWeatherTool;
import com.changlu.planner.features.command.AiCommandService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class TravelModule implements SubagentModule {
  private final ModelClient model;
  private final AiCommandService commands;
  private final WebSearchTool search;

  public TravelModule(ModelClient model, AiCommandService commands, WebSearchTool search) {
    this.model = model; this.commands = commands; this.search = search;
  }

  @Override public void register(SubagentRegistry subagents, ToolRegistry tools) {
    tools.register(new DestinationResearchTool(search));
    tools.register(new TravelWeatherTool());
    tools.register(new RouteEstimateTool());
    tools.register(new OpeningHoursTool(search));
    tools.register(new TravelPlanValidationTool());
    tools.register(new TravelDraftTool(commands));
    subagents.register(new TravelSubagent(new ModelTravelPlanner(model), tools, new TravelPolicy(),
        schema("input.schema.json"), schema("output.schema.json")));
  }

  private JsonObject schema(String name) {
    String path = "/subagents/travel/" + name;
    try (InputStream input = TravelModule.class.getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("缺少 Travel Schema：" + path);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException("无法读取 Travel Schema：" + path, error);
    }
  }
}
