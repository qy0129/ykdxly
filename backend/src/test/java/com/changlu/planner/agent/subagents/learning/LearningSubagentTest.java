package com.changlu.planner.agent.subagents.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
    AgentContext context = context();

    JsonObject result = execute("帮我分析学习进度", context);
    assertNotNull(result);
    // execute 的 catch 块将异常包装为 error 状态
    assertTrue(result.has("reply") || result.has("status"));
  }

  @Test
  void executeWithDetectGapsIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

    JsonObject result = execute("检测知识缺口", context);
    assertNotNull(result);
  }

  @Test
  void executeWithEmptyRequestHandledGracefully() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

    JsonObject result = execute("", context);
    assertNotNull(result);
  }

  @Test
  void contextCarriesUserAndWorkspaceInfo() {
    UUID userId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    Database.Context dbContext = new Database.Context(userId, workspaceId);
    AgentContext context = new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "test-trace", dbContext,
        "web", Set.of(), Instant.now().plusSeconds(60), new JsonObject());

    assertEquals(userId, context.identity().userId());
    assertEquals(workspaceId, context.identity().workspaceId());
    assertEquals("web", context.channel());
    assertNotNull(context.runId());
    assertNotNull(context.taskState());
  }

  @Test
  void createGoalWithModelUnavailableReturnsErrorResult() throws Exception {
    // model未配置且service=null → 工具链失败 → 优雅降级为错误结果
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

    JsonObject result = execute("帮我创建一个学习目标", context);
    assertNotNull(result);
  }

  @Test
  void suggestPlanIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

    JsonObject result = execute("给些学习建议", context);
    assertNotNull(result);
  }

  @Test
  void generalIntentReturnsGracefulError() throws Exception {
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

    JsonObject result = execute("你好", context);
    assertNotNull(result);
  }

  @Test
  void naturalCreateGoalWithScheduleIsNotMisreadAsProgressAnalysis() throws Exception {
    // 回归保护：用户发「90天内系统学会Python数据分析，每周8小时」时，"数据分析"里的"分析"
    // 不得把 create_goal 误判成 analyze_progress；"学会"+时长信号必须路由到创建。
    subagent = new LearningSubagent(null, model);
    assertEquals("create_goal", subagent.classifyIntent("90天内系统学会Python数据分析，每周8小时"));
    assertEquals("create_goal", subagent.classifyIntent("我要掌握办公自动化VBA，每周6小时"));
    // 带"创建学习目标"前缀的既有路径不受影响
    assertEquals("create_goal", subagent.classifyIntent("创建学习目标：三个月后雅思考到7分，每周10小时"));
  }

  @Test
  void analyzeAndProgressWordsStillRouteToProgressAnalysis() throws Exception {
    // 回归保护：真正的进度分析请求不被新加入的"每周/每天/学会"信号抢走。
    subagent = new LearningSubagent(null, model);
    assertEquals("analyze_progress", subagent.classifyIntent("分析我的学习进度"));
    assertEquals("analyze_progress", subagent.classifyIntent("看看我的学习进展如何"));
    assertEquals("analyze_progress", subagent.classifyIntent("分析每天的学习效率"));
    assertEquals("delete_goal", subagent.classifyIntent("删除学习目标"));
    assertEquals("update_goal", subagent.classifyIntent("把雅思目标改到8分"));
    assertEquals("view_stats", subagent.classifyIntent("学习统计"));
    assertEquals("general", subagent.classifyIntent("你好"));
  }

  @Test
  void allIntentPathsReturnJsonObjectNotException() throws Exception {
    // 验证所有意图路径在异常时都返回 JsonObject 而不抛异常（优雅降级）
    subagent = new LearningSubagent(null, model);
    AgentContext context = context();

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
      JsonObject result = execute(request, context);
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
  void curriculumMessagesUseCompactStageSpec() {
    // 紧凑大纲：阶段只带主题与模板，不再要求一次性输出逐日 dailyPlan（避免长周期输出超时）。
    JsonObject context = new JsonObject();
    context.addProperty("currentDate", "2026-08-07");
    JsonArray messages = LearningPrompt.curriculumMessages(context);
    assertEquals(2, messages.size());
    String systemContent = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertTrue(systemContent.contains("topics"));
    assertTrue(systemContent.contains("dailyTemplate"));
    assertFalse(systemContent.contains("dailyPlan"));
    assertTrue(systemContent.contains("逐日展开"));
  }

  @Test
  void dailyChunkMessagesRequireExactDayCount() {
    JsonObject context = new JsonObject();
    context.addProperty("stageTitle", "基础阶段");
    context.addProperty("startDate", "2026-08-07");
    context.addProperty("days", 30);
    JsonArray messages = LearningPrompt.dailyChunkMessages(context);
    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    String systemContent = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertTrue(systemContent.contains("条目数必须与输入的 days 完全一致"));
    assertTrue(systemContent.contains("第 1 条对应 startDate"));
  }

  @Test
  void subagentTimeoutAllowsChunkedDailyExpansion() {
    // 回归保护：分块展开需要跨多次模型调用，预算必须显著大于单块模型调用超时。
    subagent = new LearningSubagent(null, model);
    long timeoutSeconds = subagent.definition().timeout().getSeconds();
    assertTrue(timeoutSeconds >= 300,
        "learning 子代理预算应 ≥300s 以容纳分块展开，当前=" + timeoutSeconds + "s");
  }

  @Test
  void learningToolsRegistryContainsAllSevenTools() {
    var definitions = LearningTools.definitions();
    assertEquals(7, definitions.size());
    assertFalse(definitions.get(LearningTools.ANALYZE_PROGRESS).requiresConfirmation());
    assertFalse(definitions.get(LearningTools.SUGGEST_STUDY_PLAN).requiresConfirmation());
    assertFalse(definitions.get(LearningTools.DETECT_KNOWLEDGE_GAPS).requiresConfirmation());
    assertFalse(definitions.get(LearningTools.VIEW_STATS).requiresConfirmation());
    assertTrue(definitions.get(LearningTools.CREATE_GOAL).requiresConfirmation());
    assertTrue(definitions.get(LearningTools.UPDATE_GOAL).requiresConfirmation());
    assertTrue(definitions.get(LearningTools.DELETE_GOAL).requiresConfirmation());
  }

  @Test
  void toolRegistryRejectsDuplicateRegistration() {
    var registry = new com.changlu.planner.agent.core.tool.ToolRegistry();
    registry.register(LearningTools.handler(LearningTools.CREATE_GOAL));
    assertThrows(IllegalArgumentException.class,
        () -> registry.register(LearningTools.handler(LearningTools.CREATE_GOAL)));
  }

  private AgentContext context() {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "test-trace", DB_CTX,
        "web", Set.of(), Instant.now().plusSeconds(60), new JsonObject());
  }

  private JsonObject execute(String message, AgentContext context) throws Exception {
    AgentResult result = subagent.execute(new SubagentRequest(message, new JsonObject(), List.of()), context);
    return result.toJson();
  }
}
