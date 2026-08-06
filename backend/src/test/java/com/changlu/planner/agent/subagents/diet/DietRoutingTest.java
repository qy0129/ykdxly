package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.AgentRouter;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.registry.SubagentRegistry;
import com.changlu.planner.agent.subagents.diet.tools.DietDraftTool;
import com.changlu.planner.agent.subagents.diet.tools.NutritionReferenceTool;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 路由正确性、注册元数据完整性与场景冲突（设计 §10.3 / §11：DietRoutingTest）。 */
final class DietRoutingTest {
  @Test void modelCanSelectRegisteredDietSubagent() throws Exception {
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("executorType", "subagent"); result.addProperty("executorName", "diet");
      return result;
    });
    AgentRouter.Decision decision = router.route("帮我安排减脂餐", false, planningTools(), registry(dietOnly()));
    assertEquals("diet", decision.executorName());
  }

  @Test void metadataFallbackSelectsDietWithoutRouterKeywordBranch() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("帮我制定减脂餐计划", false, planningTools(), registry(dietOnly()));
    assertEquals("subagent", decision.executorType());
    assertEquals("diet", decision.executorName());
  }

  @Test void unrelatedRequestDoesNotAccidentallyRouteToDiet() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("查询今天的待办", false, planningTools(), registry(dietOnly()));
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  @Test void dietPlanRequestCorrectedToDietEvenWhenModelRoutesToPlanning() throws Exception {
    // 模型把"安排饮食计划"错误路由到 planning → 安全网纠正到 diet（否则饮食请求会被 planning 抢走）。
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("action", "execute");
      result.addProperty("executorType", "tool");
      result.addProperty("executorName", "planning.assistant");
      return result;
    });
    AgentRouter.Decision decision = router.route("帮我安排饮食计划并保存到我的计划", false,
        planningTools(), registry(dietOnly()));
    assertEquals("subagent", decision.executorType());
    assertEquals("diet", decision.executorName());
  }

  @Test void longestScenarioMatchPrefersMemoryOverDietOverlap() throws Exception {
    // 设计 §10.3：饮食 与 Memory 的 记住 场景重叠，bestMatch 按最长场景词命中。
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("记住我的饮食偏好", false, planningTools(),
        registry(stub("diet", List.of("减脂餐", "健康饮食", "一周食谱")),
            stub("memory", List.of("记住我的", "长期记忆"))));
    assertEquals("subagent", decision.executorType());
    assertEquals("memory", decision.executorName());
  }

  @Test void dietModuleRegistersToolAndSubagentMetadata() {
    SubagentRegistry subagents = new SubagentRegistry();
    com.changlu.planner.agent.core.tool.ToolRegistry tools =
        new com.changlu.planner.agent.core.tool.ToolRegistry();
    new DietModule(null, null, null).register(subagents, tools);

    assertTrue(tools.require(NutritionReferenceTool.NAME) != null);
    assertTrue(tools.require(DietDraftTool.NAME) != null);
    SubagentDefinition definition = subagents.require("diet").definition();
    assertEquals("diet", definition.name());
    assertEquals("1.0.0", definition.version());
    assertTrue(definition.supportedScenarios().contains("减脂餐"));
    assertTrue(definition.supportedScenarios().contains("健康饮食"));
    assertTrue(definition.unsupportedScenarios().contains("疾病治疗"));
    assertTrue(definition.allowedTools().contains(NutritionReferenceTool.NAME));
    assertTrue(definition.allowedTools().contains(DietDraftTool.NAME));
    assertTrue(definition.networkAllowed());
    assertTrue(definition.writeAllowed());
    assertEquals(Duration.ofSeconds(420), definition.timeout());
    assertEquals(3, definition.maxIterations());
  }

  private SubagentRegistry registry(Subagent... registered) {
    SubagentRegistry registry = new SubagentRegistry();
    for (Subagent subagent : registered) registry.register(subagent);
    return registry;
  }

  private Subagent dietOnly() {
    return stub("diet", List.of("健康饮食", "饮食计划", "减脂餐", "减肥餐", "增肌餐", "健身餐",
        "一周食谱", "每日菜单", "控糖饮食", "营养搭配", "食谱推荐", "食物热量", "写饮食计划"));
  }

  private Subagent stub(String name, List<String> scenarios) {
    return new Subagent() {
      @Override public SubagentDefinition definition() {
        return new SubagentDefinition(name, "1.0.0", name, scenarios, List.of(),
            new JsonObject(), new JsonObject(), Set.of(), true, true, Duration.ofSeconds(30), 2);
      }
      @Override public AgentResult execute(SubagentRequest request, AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    };
  }

  private com.changlu.planner.agent.core.tool.ToolRegistry planningTools() {
    return new com.changlu.planner.agent.core.tool.ToolRegistry();
  }
}
