package com.changlu.planner.agent.subagents.travel;

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
import com.changlu.planner.agent.subagents.travel.tools.DestinationResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelDraftTool;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TravelSubagentTest {
  @Test void normalizesRelativeDateAndPlannerIntent() {
    TravelPolicy policy = new TravelPolicy();
    SubagentRequest normalized = policy.normalizeRequest(new SubagentRequest(
        "我要去山东青岛玩，帮我做一个旅游计划，明天出发，呆十天，就我一个人，节奏轻松",
        new JsonObject(), List.of()));

    assertEquals("山东青岛", normalized.arguments().get("destination").getAsString());
    assertEquals(LocalDate.now().plusDays(1).toString(), normalized.arguments().get("startDate").getAsString());
    assertEquals(LocalDate.now().plusDays(10).toString(), normalized.arguments().get("endDate").getAsString());
    assertEquals(1, normalized.arguments().get("travelers").getAsInt());
    assertEquals("relaxed", normalized.arguments().get("pace").getAsString());
    assertTrue(normalized.arguments().get("saveToPlanner").getAsBoolean());
  }

  @Test void returnsPreviewWithoutCreatingDraft() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      TravelSubagent subagent = subagent(toPlan("[]"), tools);
      AgentResult result = subagent.execute(new SubagentRequest("推荐一个北京三日游", requestArgs(), List.of()),
          context(Set.of("travel:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(0, drafts.get());
      assertFalse(result.requiresConfirmation());
      assertEquals(1, result.data().getAsJsonArray("sources").size());
    }
  }

  @Test void asksForMissingInformation() throws Exception {
    try (ToolRegistry tools = tools(new AtomicInteger())) {
      JsonObject generated = toPlan("[\"你想去哪个城市？\"]");
      generated.getAsJsonObject("request").addProperty("destination", "");
      AgentResult result = subagent(generated, tools).execute(
          new SubagentRequest("帮我做个旅行方案", new JsonObject(), List.of()), context(Set.of("travel:read")));

      assertEquals(AgentStatus.WAITING_USER, result.status());
      assertEquals(1, result.data().getAsJsonArray("questions").size());
    }
  }

  @Test void writeRequestWaitsForPlanApprovalBeforeCreatingDraft() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("创建一个北京旅行计划", requestArgs(), List.of()),
          context(Set.of("travel:read", "planning:write")));

      assertEquals(AgentStatus.WAITING_USER, result.status());
      assertFalse(result.requiresConfirmation());
      assertTrue(result.data().get("planReview").getAsBoolean());
      assertEquals(0, drafts.get());
    }
  }

  @Test void approvedPlanCreatesConfirmationDraft() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("确认行程，生成写入计划和日历草案", requestArgs(), List.of()),
          context(Set.of("travel:read", "planning:write")));

      assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
      assertTrue(result.requiresConfirmation());
      assertEquals("draft-1", result.draftId());
      assertEquals(1, drafts.get());
    }
  }

  @Test void refusesBookingAndPaymentRequests() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("帮我订票并替我付款", new JsonObject(), List.of()),
          context(Set.of("travel:read", "planning:write")));
      assertEquals(AgentStatus.FAILED, result.status());
      assertEquals("TRAVEL_UNSUPPORTED_OPERATION", result.errors().get(0).code());
      assertEquals(0, drafts.get());
    }
  }

  @Test void doesNotHidePermissionFailuresAsResearchDegradation() {
    try (ToolRegistry tools = tools(new AtomicInteger())) {
      assertThrows(SecurityException.class, () -> subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("推荐一个北京三日游", requestArgs(), List.of()), context(Set.of())));
    }
  }

  private TravelSubagent subagent(JsonObject generated, ToolRegistry tools) {
    return new TravelSubagent((request, sources, sharedContext) -> generated.deepCopy(), tools, new TravelPolicy(),
        new JsonObject(), new JsonObject());
  }

  private ToolRegistry tools(AtomicInteger drafts) {
    ToolRegistry tools = new ToolRegistry();
    tools.register(handler(DestinationResearchTool.NAME, Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> {
          JsonArray sources = new JsonArray();
          JsonObject source = new JsonObject(); source.addProperty("title", "北京文旅");
          sources.add(source);
          JsonObject data = new JsonObject(); data.add("sources", sources);
          return AgentResult.completed("资料完成", data, "trace");
        }));
    tools.register(handler(TravelDraftTool.NAME, Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE,
        ToolSideEffect.INTERNAL_WRITE, true, call -> {
          drafts.incrementAndGet();
          JsonObject data = new JsonObject();
          JsonObject draft = new JsonObject(); draft.addProperty("id", "draft-1"); data.add("draft", draft);
          return new AgentResult("1.0", AgentStatus.WAITING_CONFIRMATION, "请确认旅行计划草案", data,
              List.of(), "trace", true, "draft-1");
        }));
    return tools;
  }

  private ToolHandler handler(String name, Set<String> permissions, ToolRiskLevel risk,
                              ToolSideEffect sideEffect, boolean confirmation,
                              ToolWork work) {
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

  private JsonObject toPlan(String questions) {
    return JsonParser.parseString("""
        {"message":"北京三日旅行方案","request":{"destination":"北京","origin":"","startDate":"2026-09-01",
        "endDate":"2026-09-03","travelers":1,"budget":{"amount":3000,"currency":"CNY"},"pace":"balanced",
        "interests":[],"constraints":[]},"days":[{"date":"2026-09-01","title":"故宫"}],
        "preparationTasks":[],"budgetEstimate":{"amount":3000,"currency":"CNY","estimated":true},
        "risks":[],"questions":%s,"planningInstruction":"创建北京三日旅行计划"}
        """.formatted(questions)).getAsJsonObject();
  }

  @Test void fallbackPlanRespectsRelaxedDailyActivityLimit() throws Exception {
    try (ToolRegistry tools = tools(new AtomicInteger())) {
      JsonObject arguments = requestArgs();
      arguments.addProperty("pace", "relaxed");
      TravelSubagent subagent = new TravelSubagent((request, sources, sharedContext) -> {
        throw new IllegalStateException("model_timeout");
      }, tools, new TravelPolicy(), new JsonObject(), new JsonObject());

      AgentResult result = subagent.execute(new SubagentRequest("创建一个轻松的北京旅行计划", arguments, List.of()),
          context(Set.of("travel:read", "planning:write")));

      for (var dayElement : result.data().getAsJsonArray("days")) {
        int minutes = 0;
        for (var activityElement : dayElement.getAsJsonObject().getAsJsonArray("activities")) {
          minutes += activityElement.getAsJsonObject().get("durationMinutes").getAsInt();
        }
        assertTrue(minutes <= 240);
      }
      JsonArray days = result.data().getAsJsonArray("days");
      assertFalse(days.get(0).getAsJsonObject().get("title").getAsString()
          .equals(days.get(1).getAsJsonObject().get("title").getAsString()));
      assertFalse(result.data().getAsJsonArray("risks").asList().stream()
          .anyMatch(risk -> risk.isJsonObject() && risk.getAsJsonObject().has("code")
              && "DAILY_ACTIVITY_LIMIT_EXCEEDED".equals(risk.getAsJsonObject().get("code").getAsString())));
      JsonObject modelRisk = result.data().getAsJsonArray("risks").asList().stream()
          .filter(risk -> risk.isJsonObject() && risk.getAsJsonObject().has("code")
              && "MODEL_UNAVAILABLE".equals(risk.getAsJsonObject().get("code").getAsString()))
          .findFirst().orElseThrow().getAsJsonObject();
      assertEquals("timeout", modelRisk.get("causeCategory").getAsString());
    }
  }

  @Test void boundsExternalTextBeforeSendingFactsToTheModel() {
    JsonObject facts = new JsonObject();
    facts.add("locationContext", new JsonObject());
    facts.add("weather", new JsonArray());
    JsonArray attractions = new JsonArray();
    JsonObject attraction = new JsonObject();
    attraction.addProperty("attractionId", "poi-1");
    attraction.addProperty("evidenceText", "景".repeat(500));
    attractions.add(attraction);
    facts.add("attractions", attractions);
    JsonArray sources = new JsonArray();
    JsonObject source = new JsonObject();
    source.addProperty("summary", "攻".repeat(1000));
    sources.add(source);
    facts.add("sources", sources);

    TravelSubagent subagent = new TravelSubagent(null, null, null, new JsonObject(), new JsonObject());
    JsonObject compact = subagent.plannerFacts(facts);

    assertEquals(243, compact.getAsJsonArray("attractions").get(0).getAsJsonObject()
        .get("evidenceText").getAsString().length());
    assertEquals(403, compact.getAsJsonArray("sources").get(0).getAsJsonObject()
        .get("summary").getAsString().length());
  }

  private JsonObject requestArgs() {
    JsonObject arguments = new JsonObject(); arguments.addProperty("destination", "北京");
    arguments.addProperty("startDate", "2026-09-01"); arguments.addProperty("endDate", "2026-09-03");
    return arguments;
  }

  private AgentContext context(Set<String> permissions) {
    UUID runId = UUID.randomUUID();
    return new AgentContext(runId, UUID.randomUUID(), "trace", new Database.Context(UUID.randomUUID(),
        UUID.randomUUID()), "web", permissions, Instant.now().plusSeconds(5), new JsonObject());
  }

  @FunctionalInterface private interface ToolWork { AgentResult execute(ToolCall call) throws Exception; }
}
