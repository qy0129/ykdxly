package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.changlu.planner.agent.core.AgentRouter;
import com.changlu.planner.agent.core.ToolDefinition;
import com.changlu.planner.agent.core.ToolRegistry;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class TravelRoutingTest {
  @Test void modelCanSelectRegisteredTravelSubagent() throws Exception {
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("executorType", "subagent"); result.addProperty("executorName", "travel");
      return result;
    });
    AgentRouter.Decision decision = router.route("北京旅行", false, planningTools(), registry());
    assertEquals("travel", decision.executorName());
  }

  @Test void metadataFallbackSelectsTravelWithoutRouterKeywordBranch() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("帮我做一个北京旅游计划", false, planningTools(), registry());
    assertEquals("subagent", decision.executorType());
    assertEquals("travel", decision.executorName());
  }

  @Test void unrelatedRequestDoesNotAccidentallyRouteToTravel() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("查询今天的待办", false, planningTools(), registry());
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  private SubagentRegistry registry() {
    SubagentRegistry registry = new SubagentRegistry();
    registry.register(new Subagent() {
      @Override public SubagentDefinition definition() {
        return new SubagentDefinition("travel", "1.0.0", "旅行规划", List.of("旅游", "旅行"), List.of(),
            new JsonObject(), new JsonObject(), Set.of(), true, true, Duration.ofSeconds(30), 2);
      }
      @Override public AgentResult execute(SubagentRequest request, AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    });
    return registry;
  }

  private ToolRegistry planningTools() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(new ToolDefinition("planning.assistant", "核心计划能力", "tool", true));
    return tools;
  }
}
