package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.registry.SubagentModule;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.diet.tools.DietDraftTool;
import com.changlu.planner.agent.subagents.diet.tools.NutritionReferenceTool;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.features.command.AiCommandService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 组合根（规范第十三条）：只允许 register 注入两个 Registry，注册 2 个 Tool + 1 个 Subagent。 */
public final class DietModule implements SubagentModule {
  private final ModelClient model;
  private final AiCommandService commands;
  private final WebSearchTool search;

  public DietModule(ModelClient model, AiCommandService commands, WebSearchTool search) {
    this.model = model; this.commands = commands; this.search = search;
  }

  @Override public void register(SubagentRegistry subagents, ToolRegistry tools) {
    tools.register(new NutritionReferenceTool(search));
    tools.register(new DietDraftTool(commands));
    subagents.register(new DietSubagent(new ModelDietPlanner(model), tools, new DietPolicy(),
        new ModelDietArgumentExtractor(model),
        schema("input.schema.json"), schema("output.schema.json")));
  }

  private JsonObject schema(String name) {
    String path = "/subagents/diet/" + name;
    try (InputStream input = DietModule.class.getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("缺少 Diet Schema：" + path);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException("无法读取 Diet Schema：" + path, error);
    }
  }
}
