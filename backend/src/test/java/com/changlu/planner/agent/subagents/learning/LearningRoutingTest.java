package com.changlu.planner.agent.subagents.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.changlu.planner.agent.core.AgentRouter;
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

/**
 * 学习目标写操作必须确定性路由到 learning Subagent 生成待确认草案，
 * 防止主 Agent 模型把这类请求当普通对话直接 complete（只回复承诺、不真正建草案）。
 */
final class LearningRoutingTest {

  @Test void modelCanSelectRegisteredLearningSubagent() throws Exception {
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("executorType", "subagent"); result.addProperty("executorName", "learning");
      return result;
    });
    AgentRouter.Decision decision = router.route("分析我的学习进度", false, planningTools(),
        registry(learningOnly()));
    assertEquals("learning", decision.executorName());
  }

  @Test void createLearningGoalRoutesToLearningEvenWhenModelCompletesWithProse() throws Exception {
    // 复现用户报告场景：模型把"创建学习目标"当普通对话直接 complete，只回复承诺而不派发执行器。
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("action", "complete");
      result.addProperty("reply", "我将为您生成待确认草案，请审阅后确认执行。");
      return result;
    });
    AgentRouter.Decision decision = router.route("创建学习目标：明年 6 月雅思考到 7 分，每周 10 小时",
        false, planningTools(), registry(learningOnly()));
    assertEquals("subagent", decision.executorType());
    assertEquals("learning", decision.executorName());
  }

  @Test void modelMisroutesPlanDeleteToLearningIsCorrectedToPlanning() throws Exception {
    // 大模型把"删除雅思的长期计划"错误路由到 learning → 安全网纠正到 planning.assistant。
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("action", "execute");
      result.addProperty("executorType", "subagent");
      result.addProperty("executorName", "learning");
      return result;
    });
    AgentRouter.Decision decision = router.route("删除雅思的长期计划", false, planningTools(),
        registry(learningOnly()));
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  @Test void modelMisroutesGoalDeleteToPlanningIsCorrectedToLearning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("action", "execute");
      result.addProperty("executorType", "tool");
      result.addProperty("executorName", "planning.assistant");
      return result;
    });
    AgentRouter.Decision decision = router.route("删除学习目标", false, planningTools(),
        registry(learningOnly()));
    assertEquals("learning", decision.executorName());
  }

  @Test void correctModelRoutingIsNotOverridden() throws Exception {
    // 模型正确路由到 learning 时，安全网不应覆盖。
    AgentRouter router = new AgentRouter(messages -> {
      JsonObject result = new JsonObject();
      result.addProperty("action", "execute");
      result.addProperty("executorType", "subagent");
      result.addProperty("executorName", "learning");
      return result;
    });
    AgentRouter.Decision decision = router.route("创建学习目标：明年 6 月雅思考到 7 分", false,
        planningTools(), registry(learningOnly()));
    assertEquals("learning", decision.executorName());
  }

  @Test void naturalLearningGoalRequestRoutesToLearning() throws Exception {
    // 用户报告场景：没有"创建"字样但表达"想学X达到Y分"，必须路由到 learning 真正建草案，
    // 否则落到 handleGeneral 只输出散文、不建草案。
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("我现在想要学高数，学7天，希望实现期末考试达到95分",
        false, planningTools(), registry(learningOnly()));
    assertEquals("subagent", decision.executorType());
    assertEquals("learning", decision.executorName());
  }

  @Test void updateLearningGoalRoutesToLearning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("把雅思目标改到 8 分，推迟到明年 6 月", false,
        planningTools(), registry(learningOnly()));
    assertEquals("learning", decision.executorName());
  }

  @Test void deleteLearningGoalRoutesToLearning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("删除学习目标", false, planningTools(),
        registry(learningOnly()));
    assertEquals("learning", decision.executorName());
  }

  @Test void deleteGenericPlanRoutesToPlanningNotLearning() throws Exception {
    // 用户报告场景："删除雅思的长期计划"删的是计划，不是学习目标，必须走 planning.assistant 的计划 CRUD。
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("删除雅思的长期计划", false, planningTools(),
        registry(learningOnly()));
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  @Test void deleteTodoRoutesToPlanning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("删除待办：买牛奶", false, planningTools(),
        registry(learningOnly()));
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  @Test void unrelatedWriteDoesNotRouteToLearning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    AgentRouter.Decision decision = router.route("创建任务：买牛奶", false, planningTools(),
        registry(learningOnly()));
    assertEquals("tool", decision.executorType());
    assertEquals("planning.assistant", decision.executorName());
  }

  @Test void documentWriteDoesNotRouteToLearning() throws Exception {
    AgentRouter router = new AgentRouter(messages -> { throw new IllegalStateException("model unavailable"); });
    // 含"文件"且带雅思关键词：不应被学习预路由抢走，应保持 document 职责。
    AgentRouter.Decision decision = router.route("删除雅思复习资料文件", false, planningTools(),
        registry(learningOnly()));
    assertEquals("document", decision.executorName());
  }

  private SubagentRegistry registry(Subagent... registered) {
    SubagentRegistry registry = new SubagentRegistry();
    for (Subagent subagent : registered) registry.register(subagent);
    return registry;
  }

  private Subagent learningOnly() {
    return new Subagent() {
      @Override public SubagentDefinition definition() {
        return new SubagentDefinition("learning", "1.0.0", "学习规划",
            List.of("学习目标", "学习进度", "学习计划", "课程", "知识梳理"), List.of(),
            new JsonObject(), new JsonObject(), Set.of(), true, true, Duration.ofSeconds(30), 2);
      }
      @Override public AgentResult execute(SubagentRequest request, AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    };
  }

  private com.changlu.planner.agent.core.tool.ToolRegistry planningTools() {
    com.changlu.planner.agent.core.tool.ToolRegistry registry =
        new com.changlu.planner.agent.core.tool.ToolRegistry();
    registry.register(new com.changlu.planner.agent.core.tool.ToolHandler() {
      @Override public com.changlu.planner.agent.core.tool.ToolDefinition definition() {
        return new com.changlu.planner.agent.core.tool.ToolDefinition(
            "planning.assistant", "1.0.0", "计划/待办/任务/日程 CRUD",
            new JsonObject(), new JsonObject(), Set.of(),
            com.changlu.planner.agent.core.tool.ToolRiskLevel.READ_ONLY,
            com.changlu.planner.agent.core.tool.ToolSideEffect.NONE, false,
            Duration.ofSeconds(30), com.changlu.planner.agent.core.tool.RetryPolicy.none());
      }
      @Override public AgentResult execute(com.changlu.planner.agent.core.tool.ToolCall call,
                                           AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    });
    return registry;
  }
}
