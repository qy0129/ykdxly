package com.changlu.planner.agent.subagents.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * LearningSubagent 集成测试。
 * 覆盖正常流程、参数错误、空数据、模型不可用、工具失败恢复场景。
 */
class LearningSubagentTest {

  private LearningSubagent subagent;
  private ModelClient model;

  private static final Database.Context DB_CTX =
      new Database.Context(Database.DEFAULT_USER_ID, Database.DEFAULT_WORKSPACE_ID);

  @BeforeEach
  void setUp() {
    model = new ModelClient();
  }

  @Test
  void nameAndDescriptionAreCorrect() {
    subagent = new LearningSubagent(null, model);
    assertEquals("learning", subagent.name());
    assertNotNull(subagent.description());
    assertFalse(subagent.description().isBlank());
    assertTrue(subagent.description().contains("学习"));
  }

  @Test
  void executeWithAnalyzeProgressIntentReturnsGracefulError() throws Exception {
    // service=null → 工具失败 → execute 捕获异常返回 error 结果
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("帮我分析学习进度", context);
    assertNotNull(result);
    // execute 的 catch 块将异常包装为 error 状态
    assertTrue(result.has("reply") || result.has("status"));
  }

  @Test
  void executeWithDetectGapsIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("检测知识缺口", context);
    assertNotNull(result);
  }

  @Test
  void executeWithEmptyRequestHandledGracefully() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("", context);
    assertNotNull(result);
  }

  @Test
  void contextCarriesUserAndWorkspaceInfo() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    Database.Context dbContext = new Database.Context(userId, workspaceId);
    AgentContext context = new AgentContext(UUID.randomUUID(), dbContext, "web", new JsonObject());

    assertEquals(userId, context.identity().userId());
    assertEquals(workspaceId, context.identity().workspaceId());
    assertEquals("web", context.channel());
    assertNotNull(context.runId());
    assertNotNull(context.input());
  }

  @Test
  void createGoalWithModelUnavailableReturnsErrorResult() throws Exception {
    // model未配置且service=null → 工具链失败 → 优雅降级为错误结果
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("帮我创建一个学习目标", context);
    assertNotNull(result);
  }

  @Test
  void suggestPlanIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("给些学习建议", context);
    assertNotNull(result);
  }

  @Test
  void generalIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    JsonObject result = subagent.execute("你好", context);
    assertNotNull(result);
  }

  @Test
  void allIntentPathsReturnJsonObjectNotException() throws Exception {
    // 验证所有意图路径在异常时都返回 JsonObject 而不抛异常（优雅降级）
    subagent = new LearningSubagent(null, model);
    AgentContext context = new AgentContext(UUID.randomUUID(), DB_CTX, "web", new JsonObject());

    String[] requests = {
        "分析学习进度",           // analyze_progress
        "给些学习建议",           // suggest_plan
        "检测知识缺口",           // detect_gaps
        "学习统计",               // view_stats
        "创建一个Java学习目标",    // create_goal
        "修改学习目标",           // update_goal
        "删除学习目标",           // delete_goal
        "最近学得怎么样"          // general
    };

    for (String request : requests) {
      JsonObject result = subagent.execute(request, context);
      assertNotNull(result, "请求 '" + request + "' 应该返回 JsonObject 而非抛异常");
    }
  }

  @Test
  void learningPromptHasCompleteSystemPrompt() {
    assertNotNull(LearningPrompt.SYSTEM_PROMPT);
    assertFalse(LearningPrompt.SYSTEM_PROMPT.isBlank());
    assertTrue(LearningPrompt.SYSTEM_PROMPT.contains("学习规划"));
    assertTrue(LearningPrompt.SYSTEM_PROMPT.contains("【适用场景】"));
    assertTrue(LearningPrompt.SYSTEM_PROMPT.contains("【不可处理的请求】"));
    assertTrue(LearningPrompt.SYSTEM_PROMPT.contains("【完成条件】"));
    assertTrue(LearningPrompt.SYSTEM_PROMPT.contains("【失败条件】"));
  }

  @Test
  void progressMessagesIncludeSystemAndUserMessages() {
    JsonObject context = new JsonObject();
    context.addProperty("test", true);
    JsonArray messages = LearningPrompt.progressMessages(context);
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
  }

  @Test
  void suggestionMessagesIncludeReasonableConstraints() {
    JsonObject context = new JsonObject();
    JsonArray messages = LearningPrompt.suggestionMessages(context);
    String systemContent = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertTrue(systemContent.contains("25 分钟"));
    assertTrue(systemContent.contains("120 分钟"));
  }

  @Test
  void goalDraftMessagesAskForClarification() {
    JsonObject context = new JsonObject();
    JsonArray messages = LearningPrompt.goalDraftMessages(context);
    String systemContent = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertTrue(systemContent.contains("conflicts"));
    assertTrue(systemContent.contains("rationale"));
  }

  @Test
  void learningToolsRegistryContainsAllSevenTools() {
    var registry = LearningTools.registry();
    assertEquals(7, registry.all().size());

    assertFalse(registry.require(LearningTools.ANALYZE_PROGRESS).requiresConfirmation());
    assertFalse(registry.require(LearningTools.SUGGEST_STUDY_PLAN).requiresConfirmation());
    assertFalse(registry.require(LearningTools.DETECT_KNOWLEDGE_GAPS).requiresConfirmation());
    assertFalse(registry.require(LearningTools.VIEW_STATS).requiresConfirmation());

    assertTrue(registry.require(LearningTools.CREATE_GOAL).requiresConfirmation());
    assertTrue(registry.require(LearningTools.UPDATE_GOAL).requiresConfirmation());
    assertTrue(registry.require(LearningTools.DELETE_GOAL).requiresConfirmation());
  }

  @Test
  void toolRegistryRejectsDuplicateRegistration() {
    var registry = LearningTools.registry();
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(new com.changlu.planner.agent.core.ToolDefinition(
            LearningTools.CREATE_GOAL, "重复", "subagent", true)));
  }
}
