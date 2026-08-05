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
    DietPlannerModel planner = (request, sources, targets, missing) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = message -> JsonParser.parseString("""
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

  @Test void providedArgumentsTakePriorityOverExtraction() throws Exception {
    // 显式 arguments 已提供 weightKg=80，提取器也返回 weightKg=70：以显式为主；未提供的 targetWeightKg 由提取补齐
    AtomicInteger drafts = new AtomicInteger();
    DietRequest[] captured = new DietRequest[1];
    DietPlannerModel planner = (request, sources, targets, missing) -> { captured[0] = request; return toPlan("[]"); };
    DietArgumentExtractor extractor = message -> JsonParser.parseString("""
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
    DietPlannerModel plan = (request, sources, targets, missing) ->
        toPlan("[\"为了计算你的营养目标，还需要：目标、年龄、性别、身高、体重、日常活动量。\"]");
    DietArgumentExtractor failing = message -> { throw new IllegalStateException("model down"); };
    DietArgumentExtractor empty = message -> new JsonObject();
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
    return new DietSubagent((request, sources, targets, missing) -> generated.deepCopy(),
        tools, new DietPolicy(), new JsonObject(), new JsonObject());
  }

  private ToolRegistry tools(AtomicInteger drafts, ResearchWork research) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(handler(NutritionReferenceTool.NAME, Set.of("diet:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> research.run()));
    tools.register(handler(DietDraftTool.NAME, Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE,
        ToolSideEffect.INTERNAL_WRITE, true, call -> {
          drafts.incrementAndGet();
          JsonObject data = new JsonObject();
          JsonObject draft = new JsonObject(); draft.addProperty("id", "draft-1"); data.add("draft", draft);
          return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION, "请确认饮食计划草案", data,
              List.of(), "trace", true, "draft-1");
        }));
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
    UUID runId = UUID.randomUUID();
    return new AgentContext(runId, UUID.randomUUID(), "trace", new Database.Context(UUID.randomUUID(),
        UUID.randomUUID()), "web", permissions, Instant.now().plusSeconds(5), new JsonObject());
  }

  @FunctionalInterface private interface ToolWork { AgentResult execute(ToolCall call) throws Exception; }
  @FunctionalInterface private interface ResearchWork { AgentResult run() throws Exception; }
}
