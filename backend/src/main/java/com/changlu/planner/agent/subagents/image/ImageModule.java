package com.changlu.planner.agent.subagents.image;

import com.changlu.planner.agent.core.registry.SubagentModule;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationService;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationTool;
import com.changlu.planner.agent.subagents.image.tools.JdbcImageGenerationRepository;
import com.changlu.planner.agent.subagents.image.tools.SiliconFlowImageGenerationProvider;
import com.changlu.planner.shared.database.Database;

/** 文生图能力模块：注册领域 Tool 与 Subagent，供组合根统一装配。 */
public final class ImageModule implements SubagentModule {
  private final ImagePrompt prompt = new ImagePrompt();
  private final ImageGenerationService service;

  public ImageModule(Database database) {
    this.service = new ImageGenerationService(
        new SiliconFlowImageGenerationProvider(),
        new JdbcImageGenerationRepository(database),
        prompt);
  }

  @Override public void register(SubagentRegistry subagents, ToolRegistry tools) {
    tools.register(new ImageGenerationTool(service, prompt));
    subagents.register(new ImageSubagent(tools, prompt));
  }
}