package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.diet.tools.DietDraftTool;
import com.changlu.planner.agent.subagents.diet.tools.NutritionReferenceTool;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 正常流程 / 追问 / 草案 / 拒绝 / 权限（设计 §11：DietSubagentTest）。 */
final class DietSubagentTest {
  @Test void returnsPreviewWithoutCreatingDraft() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("给我一周减脂餐的建议", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(0, drafts.get());
      assertFalse(result.requiresConfirmation());
      assertEquals(1, result.data().getAsJsonArray("sources").size());
      // 营养目标来自确定性计算器，而不是模型
      assertEquals(2172, result.data().getAsJsonObject("dailyTargets").get("energyKcal").getAsInt());
      // 强制健康声明（设计 §2.3）
      assertTrue(hasRisk(result, "DIET_ESTIMATED_TARGETS"));
      assertTrue(hasRisk(result, "DIET_MEDICAL_ADVICE"));
      assertTrue(hasRisk(result, "DIET_NO_EFFECT_GUARANTEE"));
    }
  }

  @Test void asksForMissingInformationInOnePass() throws Exception {
    try (ToolRegistry tools = tools(new AtomicInteger(), () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[\"为了计算你的营养目标，还需要：目标、年龄、性别、身高、体重、日常活动量。\"]"), tools)
          .execute(new SubagentRequest("帮我做个饮食计划", new JsonObject(), List.of()),
              context(Set.of("diet:read")));

      assertEquals(AgentStatus.WAITING_USER, result.status());
      assertEquals(1, result.data().getAsJsonArray("questions").size());
      assertEquals(0, result.data().getAsJsonObject("dailyTargets").size());
    }
  }

  @Test void writeRequestCreatesConfirmationDraftOnly() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[]", "创建四周健康饮食计划"), tools).execute(
          new SubagentRequest("帮我制定减脂餐计划并保存到计划", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
      assertTrue(result.requiresConfirmation());
      assertEquals("draft-1", result.draftId());
      assertEquals(1, drafts.get());
    }
  }

  @Test void writeRequestWithBlankInstructionCompletesWithoutDraft() throws Exception {
    // 消息含"制定"触发 writeRequested，但模型未生成 planningInstruction：
    // 不应调用草案工具（否则 DIET_PLANNING_INSTRUCTION_REQUIRED），降级为直接返回完整方案
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[]", ""), tools).execute(
          new SubagentRequest("请为我制定一周减脂饮食计划", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(0, drafts.get());
      assertFalse(result.requiresConfirmation());
      assertTrue(result.data().getAsJsonArray("mealPlan").size() > 0);
    }
  }

  @Test void refusesMedicalTreatmentRequests() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("帮我治疗糖尿病，顺便安排饮食", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));
      assertEquals(AgentStatus.FAILED, result.status());
      assertEquals("DIET_MEDICAL_UNSUPPORTED", result.errors().get(0).code());
      assertEquals(0, drafts.get());
    }
  }

  @Test void refusesMinorProfile() throws Exception {
    JsonObject arguments = fullArguments();
    arguments.getAsJsonObject("profile").addProperty("age", 16);
    try (ToolRegistry tools = tools(new AtomicInteger(), () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("帮我安排一周减脂餐", arguments, List.of()),
          context(Set.of("diet:read", "planning:write")));
      assertEquals(AgentStatus.FAILED, result.status());
      assertEquals("DIET_MEDICAL_UNSUPPORTED", result.errors().get(0).code());
    }
  }

  @Test void doesNotHidePermissionFailuresAsResearchDegradation() {
    try (ToolRegistry tools = tools(new AtomicInteger(), () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      assertThrows(SecurityException.class, () -> subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("帮我安排一周减脂餐", fullArguments(), List.of()), context(Set.of())));
    }
  }

  @Test void researchFailureDegradesGracefullyWithRiskItem() throws Exception {
    try (ToolRegistry tools = tools(new AtomicInteger(), () -> { throw new IllegalStateException("network down"); })) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("给我一周减脂餐的建议", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));
      assertEquals(AgentStatus.COMPLETED, result.status());
      assertTrue(hasRisk(result, "EXTERNAL_SERVICE_UNAVAILABLE"));
    }
  }

  @Test void extractionFillsMissingFieldsFromMessage() throws Exception {
    // Web 入口只传 message、不带 arguments：提取器把自然语言补成结构参数，planner 收到完整 request
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = (message, shared) -> JsonParser.parseString("""
        {"goal":"减脂","profile":{"age":30,"sex":"female","heightCm":162,"weightKg":70,
        "targetWeightKg":58,"activityLevel":"light"},"dietaryType":"balanced","dislikes":["香菜"]}
        """).getAsJsonObject();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("减脂，女，162cm，70kg，目标减脂到58kg，平时久坐", new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals("减脂", captured[0].goal());
      assertEquals("female", captured[0].profileText("sex"));
      assertEquals(70.0, captured[0].profileNumber("weightKg"));
      assertEquals(58.0, captured[0].profileNumber("targetWeightKg"));
      assertEquals("light", captured[0].profileText("activityLevel"));
      assertEquals("balanced", captured[0].dietaryType());
    }
  }

  @Test void sanitizesHallucinatedOptionalFieldsFromExtraction() throws Exception {
    // 回归：提取器幻觉非法 dietaryType（如 "paleo"）——合并后被 sanitize 删除、视同未提供。
    // 否则该值会经 DietRequest.toJson 持久化进 taskData.request，WAITING_USER resume 回放时
    // input.schema 枚举校验直接 INVALID_ARGUMENT:input.arguments.dietaryType。
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = (message, shared) -> JsonParser.parseString("""
        {"goal":"减脂","dietaryType":"paleo","profile":{"age":30,"sex":"male","heightCm":175,
        "weightKg":70,"activityLevel":"moderate"}}
        """).getAsJsonObject();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("帮我安排一周减脂餐", new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals("", captured[0].dietaryType());
      assertEquals(30.0, captured[0].profileNumber("age"));
      // 持久化的 request 不再携带非法枚举，防止第二轮 resume 崩
      JsonObject persisted = result.data().getAsJsonObject("request");
      assertFalse(persisted.has("dietaryType"));
    }
  }

  @Test void extractorReceivesSharedContextToFillProfileFromMemory() throws Exception {
    // 新 run（上一轮已 COMPLETED）下用户资料不在本轮消息里，而在最近对话/长期记忆（sharedContext）中：
    // 提取器必须收到 sharedContext 并从中补全 profile，否则 requiredFields 会再次追问用户已给过的信息。
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    String[] capturedShared = new String[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = (message, shared) -> {
      capturedShared[0] = shared;
      return JsonParser.parseString("""
          {"goal":"减脂","profile":{"age":21,"sex":"male","heightCm":178,"weightKg":65,"activityLevel":"sedentary"}}
          """).getAsJsonObject();
    };
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("帮我安排一周减脂餐", new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write"),
              "[用户] 男，21岁，体重65kg，身高178cm，办公久坐\n[长期记忆] [personal_fact] 用户男性，21岁，178cm，65kg"));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertTrue(capturedShared[0] != null && capturedShared[0].contains("178cm"));
      assertEquals(21.0, captured[0].profileNumber("age"));
      assertEquals("male", captured[0].profileText("sex"));
      assertEquals(65.0, captured[0].profileNumber("weightKg"));
    }
  }

  @Test void deterministicProfileRecoveryPreventsReaskingWhenExtractorFails() throws Exception {
    // 回归：提取器（LLM）完全失效返回空对象时，确定性兜底必须从 sharedContext 的记忆/最近对话
    // 解析出用户本人 profile，requiredFields 为空 → 直接生成菜单，而不是再次追问用户已给过的资料
    // （对应"已知您为男性21岁…请确认"的确认死循环）。
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    // 提取器只从当前消息提取了 goal，却没能从记忆/最近对话填 profile（LLM 提取不完整的典型失败模式）
    DietArgumentExtractor failing = (message, shared) -> JsonParser.parseString("{\"goal\":\"减脂\"}").getAsJsonObject();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), failing,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("把这份减脂餐计划保存到我的计划", new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write"),
              "用户长期记忆（稳定的偏好、个性和事实，请自然遵循）：\n"
                  + "- [personal_fact] 用户男性，21岁，178cm，65kg，办公久坐\n\n"
                  + "最近对话：\n[用户] 男，21岁，体重65kg，身高178cm，办公久坐"));

      // 资料齐全 + 用户要求保存 → 直达草案确认，而不是再次追问已给过的资料
      assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
      assertTrue(result.requiresConfirmation());
      assertEquals(21.0, captured[0].profileNumber("age"));
      assertEquals("male", captured[0].profileText("sex"));
      assertEquals(65.0, captured[0].profileNumber("weightKg"));
      assertEquals("sedentary", captured[0].profileText("activityLevel"));
    }
  }

  @Test void goalRecoveredFromMessageWhenExtractorOmitsIt() throws Exception {
    // 回归：菜单已生成后用户请求"保存+排日程"——提取器偶发漏提取 goal，
    // 兜底必须从消息确定性补上目标，否则 requiredFields 缺目标 → 反复追问"是减脂、增肌、保持健康还是控糖"。
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    // 提取器只填了 profile、漏了 goal（LLM 提取不完整的典型失败模式）
    DietArgumentExtractor extractor = (message, shared) -> JsonParser.parseString("""
        {"profile":{"age":21,"sex":"male","heightCm":178,"weightKg":65,"activityLevel":"sedentary"}}
        """).getAsJsonObject();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("把这份减脂餐计划保存到我的计划，并把我每天的早午晚餐排进日程：早餐 8:00、午餐 12:00、晚餐 18:30。",
              new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write"), "用户长期记忆：\n[用户] 帮我做个减脂餐计划"));

      // goal 从消息兜底补全 → 直达草案确认，不再追问目标
      assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
      assertTrue(result.requiresConfirmation());
      assertEquals("减脂", captured[0].goal());
      assertEquals(21.0, captured[0].profileNumber("age"));
      assertEquals("sedentary", captured[0].profileText("activityLevel"));
    }
  }

  @Test void scheduleTimesInjectedIntoDraftInstruction() throws Exception {
    // 用户明确给了餐次时间（早餐8:00/午餐12:00/晚餐18:30）：即使提取器全失效、模型漏生成指令，
    // 确定性兜底也必须把时间写进交给草案工具的指令，否则日历不会出现日程。
    String[] capturedInstruction = new String[1];
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = (message, shared) -> new JsonObject();
    try (ToolRegistry tools = tools(new AtomicInteger(), () -> AgentResult.completed("ok", sourcesData(), "trace"),
        call -> {
          capturedInstruction[0] = call.arguments().get("planningInstruction").getAsString();
          JsonObject data = new JsonObject();
          JsonObject draft = new JsonObject(); draft.addProperty("id", "draft-1"); data.add("draft", draft);
          return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION, "请确认", data, List.of(), "trace", true, "draft-1");
        })) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("把这份减脂餐计划保存到我的计划，并把我每天的早午晚餐排进日程：早餐 8:00、午餐 12:00、晚餐 18:30。",
              new JsonObject(), List.of()),
          context(Set.of("diet:read", "planning:write"),
              "用户长期记忆：\n[用户] 男，21岁，体重65kg，身高178cm，办公久坐"));

      assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
      assertEquals("减脂", captured[0].goal());
      // 指令必须是确定性构造的自然中文（AiCommandService 规划代理能解析），而不是模型可能输出的 CREATE_PLAN(...) DSL
      assertTrue(capturedInstruction[0].startsWith("创建减脂一周饮食计划"));
      assertTrue(!capturedInstruction[0].contains("CREATE_PLAN"));
      assertTrue(capturedInstruction[0].contains("第1天"));
      assertTrue(capturedInstruction[0].contains("早餐安排在8:00"));
      assertTrue(capturedInstruction[0].contains("午餐安排在12:00"));
      assertTrue(capturedInstruction[0].contains("晚餐安排在18:30"));
      assertTrue(capturedInstruction[0].contains("写入日程"));
    }
  }

  @Test void writeRequestedDetectsSaveToMyPlan() {
    DietPolicy policy = new DietPolicy();
    assertTrue(policy.writeRequested(
        "把这份减脂餐计划保存到我的计划，并把我每天的早午晚餐排进日程：早餐 8:00、午餐 12:00、晚餐 18:30。",
        new JsonObject()));
    assertFalse(policy.writeRequested("帮我出一周减脂菜单", new JsonObject()));
  }

  @Test void parseScheduleTimesExtractsMealTimesFromMessage() {
    JsonObject times = DietSubagent.parseScheduleTimes(
        "把早午晚餐排进日程：早餐 8:00、午餐 12:00、晚餐 18:30。");
    assertEquals("08:00", times.get("breakfast").getAsString()); // 小时补零
    assertEquals("12:00", times.get("lunch").getAsString());
    assertEquals("18:30", times.get("dinner").getAsString());
    assertEquals(0, DietSubagent.parseScheduleTimes("帮我保存菜单").size());
  }

  @Test void withScheduleTimesOnlyAppendsWhenUserGivesTimes() {
    assertEquals("创建计划。；并把每天早餐安排在8:00、午餐安排在12:00、晚餐安排在18:30，写入日程。",
        DietSubagent.withScheduleTimes("创建计划。", "把早午晚餐排进日程：早餐 8:00、午餐 12:00、晚餐 18:30。"));
    assertEquals("创建计划。", DietSubagent.withScheduleTimes("创建计划。", "帮我保存菜单"));
    assertEquals("已含日程的计划。", DietSubagent.withScheduleTimes("已含日程的计划。", "早餐 8:00")); // 幂等
    assertEquals("创建计划。", DietSubagent.withScheduleTimes("创建计划。", "早餐 8点")); // 无 HH:mm 不追加
  }

  @Test void providedArgumentsTakePriorityOverExtraction() throws Exception {
    // 显式 arguments 已提供 weightKg=80，提取器也返回 weightKg=70：以显式为主；未提供的 targetWeightKg 由提取补齐
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing, shared) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = (message, shared) -> JsonParser.parseString("""
        {"profile":{"weightKg":70,"targetWeightKg":58}}
        """).getAsJsonObject();
    JsonObject arguments = fullArguments();
    arguments.getAsJsonObject("profile").addProperty("weightKg", 80);
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult result = new DietSubagent(planner, tools, new DietPolicy(), extractor,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("我要减脂到 58 公斤", arguments, List.of()),
          context(Set.of("diet:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(80.0, captured[0].profileNumber("weightKg"));
      assertEquals(58.0, captured[0].profileNumber("targetWeightKg"));
    }
  }

  @Test void extractionFailureFallsBackToOriginalArguments() throws Exception {
    // 提取器抛异常 / 返回空：回退为原始空参数，照常走追问流程（不阻塞）
    AtomicInteger drafts = new AtomicInteger();
    DietPlannerModel plan = (request, sources, targets, missing, shared) ->
        toPlan("[\"为了计算你的营养目标，还需要：目标、年龄、性别、身高、体重、日常活动量。\"]");
    DietArgumentExtractor failing = (message, shared) -> { throw new IllegalStateException("model down"); };
    DietArgumentExtractor empty = (message, shared) -> new JsonObject();
    try (ToolRegistry tools = tools(drafts, () -> AgentResult.completed("资料完成", sourcesData(), "trace"))) {
      AgentResult failed = new DietSubagent(plan, tools, new DietPolicy(), failing,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("帮我做个饮食计划", new JsonObject(), List.of()),
          context(Set.of("diet:read")));
      assertEquals(AgentStatus.WAITING_USER, failed.status());
      assertEquals(1, failed.data().getAsJsonArray("questions").size());

      AgentResult emptyResult = new DietSubagent(plan, tools, new DietPolicy(), empty,
          new JsonObject(), new JsonObject()).execute(
          new SubagentRequest("帮我做个饮食计划", new JsonObject(), List.of()),
          context(Set.of("diet:read")));
      assertEquals(AgentStatus.WAITING_USER, emptyResult.status());
      assertEquals(1, emptyResult.data().getAsJsonArray("questions").size());
    }
  }

  private boolean hasRisk(AgentResult result, String code) {
    JsonArray risks = result.data().getAsJsonArray("risks");
    for (var element : risks) {
      if (element.isJsonObject() && code.equals(element.getAsJsonObject().get("code").getAsString())) return true;
    }
    return false;
  }

  private DietSubagent subagent(JsonObject generated, ToolRegistry tools) {
    return new DietSubagent((request, sources, targets, missing, shared) -> generated.deepCopy(),
        tools, new DietPolicy(), new JsonObject(), new JsonObject());
  }

  private ToolRegistry tools(AtomicInteger drafts, ResearchWork research) {
    return tools(drafts, research, call -> {
      drafts.incrementAndGet();
      JsonObject data = new JsonObject();
      JsonObject draft = new JsonObject(); draft.addProperty("id", "draft-1"); data.add("draft", draft);
      return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION, "请确认饮食计划草案", data,
          List.of(), "trace", true, "draft-1");
    });
  }

  private ToolRegistry tools(AtomicInteger drafts, ResearchWork research, ToolWork draftWork) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(handler(NutritionReferenceTool.NAME, Set.of("diet:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> research.run()));
    tools.register(handler(DietDraftTool.NAME, Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE,
        ToolSideEffect.INTERNAL_WRITE, true, draftWork));
    return tools;
  }

  private ToolHandler handler(String name, Set<String> permissions, ToolRiskLevel risk,
                              ToolSideEffect sideEffect, boolean confirmation, ToolWork work) {
    return new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(name, "1.0.0", name, new JsonObject(), new JsonObject(), permissions,
            risk, sideEffect, confirmation, Duration.ofSeconds(1), RetryPolicy.none());
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
        return work.execute(call);
      }
    };
  }

  private JsonObject fullArguments() {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("goal", "减脂");
    JsonObject profile = new JsonObject();
    profile.addProperty("age", 30); profile.addProperty("sex", "male");
    profile.addProperty("heightCm", 175); profile.addProperty("weightKg", 70);
    profile.addProperty("activityLevel", "moderate");
    arguments.add("profile", profile);
    return arguments;
  }

  private JsonObject sourcesData() {
    JsonArray sources = new JsonArray();
    JsonObject source = new JsonObject(); source.addProperty("title", "中国居民膳食指南");
    sources.add(source);
    JsonObject data = new JsonObject(); data.add("sources", sources);
    return data;
  }

  private JsonObject toPlan(String questions) {
    return toPlan(questions, "");
  }

  private JsonObject toPlan(String questions, String planningInstruction) {
    return JsonParser.parseString("""
        {"message":"为你整理了一周减脂餐计划。","mealPlan":[{"day":1,"date":"","meals":[
          {"type":"breakfast","title":"燕麦鸡蛋杯","foodItems":["燕麦","鸡蛋"],"estimatedKcal":350,"notes":""}]}],
        "shoppingList":[{"item":"燕麦","category":"主食","estimatedQuantity":"500g"}],
        "recipes":[{"title":"燕麦鸡蛋杯","servings":1,"steps":["蒸 10 分钟"]}],
        "tips":["每天喝够 1.5L 水"],"risks":[],"questions":%s,"planningInstruction":"%s"}
        """.formatted(questions, planningInstruction)).getAsJsonObject();
  }

  private AgentContext context(Set<String> permissions) {
    return context(permissions, "");
  }

  private AgentContext context(Set<String> permissions, String sharedContext) {
    UUID runId = UUID.randomUUID();
    return new AgentContext(runId, UUID.randomUUID(), "trace", new Database.Context(UUID.randomUUID(),
        UUID.randomUUID()), "web", permissions, Instant.now().plusSeconds(5), new JsonObject(), sharedContext);
  }

  @FunctionalInterface private interface ToolWork { AgentResult execute(ToolCall call) throws Exception; }
  @FunctionalInterface private interface ResearchWork { AgentResult run() throws Exception; }
}
