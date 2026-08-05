package com.changlu.planner.agent.subagents.image;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.changlu.planner.agent.core.AgentRouter;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

final class ImageRoutingTest {
  @Test void modelFailureStillRoutesImageIntentToImageSubagent() throws Exception {
    SubagentRegistry subagents = new SubagentRegistry();
    subagents.register(new ImageSubagent(new ToolRegistry(), new ImagePrompt()));
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });

    AgentRouter.Decision decision = router.route("帮我画一张海报", false, new ToolRegistry(), subagents);

    assertEquals("subagent", decision.executorType());
    assertEquals(ImageSubagent.NAME, decision.executorName());
  }

  @Test void directImageToolSelectionIsNormalizedToSubagent() throws Exception {
    SubagentRegistry subagents = new SubagentRegistry();
    subagents.register(new ImageSubagent(new ToolRegistry(), new ImagePrompt()));
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject decision = new JsonObject();
      decision.addProperty("action", "execute");
      decision.addProperty("executorType", "tool");
      decision.addProperty("executorName", "image.generate");
      return decision;
    });

    AgentRouter.Decision decision = router.route("帮我画一只猫", false, new ToolRegistry(), subagents);

    assertEquals("subagent", decision.executorType());
    assertEquals(ImageSubagent.NAME, decision.executorName());
  }
}
