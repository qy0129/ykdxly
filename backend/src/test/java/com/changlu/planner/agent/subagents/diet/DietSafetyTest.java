package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;

/** 医疗场景拒绝、敏感信息脱敏、1200 kcal 下限、未成年人拒绝（设计 §8 / §11：DietSafetyTest）。 */
final class DietSafetyTest {
  private final DietPolicy policy = new DietPolicy();

  @Test void rejectsMedicalTreatmentKeywords() {
    assertTrue(policy.unsupportedRequest("帮我治疗糖尿病"));
    assertTrue(policy.unsupportedRequest("给我开个药方"));
    assertTrue(policy.unsupportedRequest("诊断一下我的血糖问题"));
    assertTrue(policy.unsupportedRequest("绝食一周排毒"));
    assertFalse(policy.unsupportedRequest("帮我安排一周健康饮食"));
  }

  @Test void rejectsMinorProfile() {
    assertTrue(policy.unsupportedProfile(request(16, List.of())));
    assertFalse(policy.unsupportedProfile(request(30, List.of())));
  }

  @Test void rejectsPregnancyInMedicalConditions() {
    assertTrue(policy.unsupportedProfile(request(30, List.of("孕妇"))));
    assertTrue(policy.unsupportedProfile(request(30, List.of("哺乳期"))));
    assertFalse(policy.unsupportedProfile(request(30, List.of("糖尿病"))));
  }

  @Test void screensChronicConditionsWithMedicalAdvice() {
    JsonArray risks = policy.medicalRiskScreen(request(30, List.of("糖尿病", "高血压")));
    assertEquals(2, risks.size());
    for (var element : risks) {
      assertEquals("DIET_MEDICAL_SCREENING", element.getAsJsonObject().get("code").getAsString());
      assertTrue(element.getAsJsonObject().get("message").getAsString().contains("咨询医生"));
    }
  }

  @Test void requiredFieldsAreCollectedInOnePass() {
    // 只有身高体重 → 一次收集其余全部必需字段（设计 §5.1 追问策略）
    DietRequest request = request(30, List.of());
    JsonObject profile = request.profile();
    profile.remove("age"); profile.remove("sex"); profile.remove("activityLevel");
    DietRequest partial = new DietRequest("", profile, "none", new JsonArray(), new JsonArray(),
        new JsonArray(), 3, 30, new JsonObject(), new JsonArray(), false);
    List<String> missing = policy.requiredFields(partial);
    assertTrue(missing.contains("目标（减脂/增肌/保持健康/控糖/均衡营养）"));
    assertTrue(missing.contains("年龄"));
    assertTrue(missing.contains("性别"));
    assertTrue(missing.contains("日常活动量"));
    assertFalse(missing.contains("身高"));
    assertFalse(missing.contains("体重"));
  }

  @Test void researchQueryExcludesPersonalProfileValues() {
    DietRequest request = request(30, List.of());
    JsonObject profile = request.profile();
    profile.addProperty("heightCm", 175); profile.addProperty("weightKg", 70);
    String query = DietSubagent.researchQuery(request);
    assertTrue(query.contains("减脂"));
    assertFalse(query.contains("175"));
    assertFalse(query.contains("70"));
  }

  @Test void errorsNeverCarrySensitiveProfileValues() throws Exception {
    try (ToolRegistry tools = tools()) {
      AgentResult result = subagent(tools).execute(
          new SubagentRequest("帮我治疗糖尿病，身高175体重70", fullArguments(), List.of()),
          context(Set.of("diet:read", "planning:write")));
      assertEquals(AgentStatus.FAILED, result.status());
      assertEquals(0, result.errors().get(0).details().size());
      assertFalse(result.message().contains("175"));
    }
  }

  @Test void energyFloorRiskSurfacesInOutput() throws Exception {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("goal", "保持健康");
    JsonObject profile = new JsonObject();
    profile.addProperty("age", 70); profile.addProperty("sex", "female");
    profile.addProperty("heightCm", 150); profile.addProperty("weightKg", 55);
    profile.addProperty("activityLevel", "sedentary");
    arguments.add("profile", profile);
    try (ToolRegistry tools = tools()) {
      AgentResult result = subagent(tools).execute(
          new SubagentRequest("给我一周饮食的建议", arguments, List.of()),
          context(Set.of("diet:read", "planning:write")));
      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(1200, result.data().getAsJsonObject("dailyTargets").get("energyKcal").getAsInt());
      assertTrue(hasRisk(result, "DIET_ENERGY_FLOOR"));
    }
  }

  private boolean hasRisk(AgentResult result, String code) {
    JsonArray risks = result.data().getAsJsonArray("risks");
    for (var element : risks) {
      if (element.isJsonObject() && code.equals(element.getAsJsonObject().get("code").getAsString())) return true;
    }
    return false;
  }

  private DietSubagent subagent(ToolRegistry tools) {
    JsonObject generated = JsonParser.parseString("""
        {"message":"为你整理了一周饮食计划。","mealPlan":[{"day":1,"date":"","meals":[
          {"type":"breakfast","title":"燕麦鸡蛋杯","foodItems":["燕麦"],"estimatedKcal":350,"notes":""}]}],
        "shoppingList":[],"recipes":[],"tips":[],"risks":[],"questions":[],"planningInstruction":""}
        """).getAsJsonObject();
    return new DietSubagent((request, sources, targets, missing) -> generated.deepCopy(),
        tools, new DietPolicy(), new JsonObject(), new JsonObject());
  }

  private ToolRegistry tools() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(NutritionReferenceTool.NAME, "1.0.0", "search", new JsonObject(),
            new JsonObject(), Set.of("diet:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE,
            false, Duration.ofSeconds(1), RetryPolicy.none());
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) {
        JsonArray sources = new JsonArray();
        JsonObject source = new JsonObject(); source.addProperty("title", "膳食指南");
        sources.add(source);
        JsonObject data = new JsonObject(); data.add("sources", sources);
        return AgentResult.completed("ok", data, context.traceId());
      }
    });
    tools.register(new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(DietDraftTool.NAME, "1.0.0", "draft", new JsonObject(),
            new JsonObject(), Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE,
            ToolSideEffect.INTERNAL_WRITE, true, Duration.ofSeconds(1), RetryPolicy.none());
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) {
        JsonObject data = new JsonObject();
        JsonObject draft = new JsonObject(); draft.addProperty("id", "draft-1"); data.add("draft", draft);
        return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION, "请确认", data,
            List.of(), "trace", true, "draft-1");
      }
    });
    return tools;
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

  private DietRequest request(int age, List<String> conditions) {
    JsonObject profile = new JsonObject();
    profile.addProperty("age", age); profile.addProperty("sex", "male");
    profile.addProperty("heightCm", 175); profile.addProperty("weightKg", 70);
    profile.addProperty("activityLevel", "moderate");
    JsonArray medical = new JsonArray();
    conditions.forEach(medical::add);
    return new DietRequest("减脂", profile, "none", new JsonArray(), new JsonArray(), medical,
        3, 30, new JsonObject(), new JsonArray(), false);
  }

  private AgentContext context(Set<String> permissions) {
    UUID runId = UUID.randomUUID();
    return new AgentContext(runId, UUID.randomUUID(), "trace", new Database.Context(UUID.randomUUID(),
        UUID.randomUUID()), "web", permissions, Instant.now().plusSeconds(5), new JsonObject());
  }
}
