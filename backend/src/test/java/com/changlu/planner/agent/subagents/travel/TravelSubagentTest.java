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
import com.changlu.planner.agent.subagents.travel.tools.OpeningHoursTool;
import com.changlu.planner.agent.subagents.travel.tools.RouteEstimateTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelDraftTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelPlanValidationTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelWeatherTool;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TravelSubagentTest {
  @Test void returnsPreviewWithoutCreatingDraft() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      TravelSubagent subagent = subagent(toPlan("[]"), tools);
      AgentResult result = subagent.execute(new SubagentRequest("推荐一个北京三日游", new JsonObject(), List.of()),
          context(Set.of("travel:read", "planning:write")));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertEquals(0, drafts.get());
      assertFalse(result.requiresConfirmation());
      assertEquals(1, result.data().getAsJsonArray("sources").size());
      assertTrue(result.data().has("weather"));
      assertTrue(result.data().has("routes"));
      assertTrue(result.data().has("openingHours"));
      assertTrue(result.data().has("validation"));
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

  @Test void writeRequestCreatesConfirmationDraftOnly() throws Exception {
    AtomicInteger drafts = new AtomicInteger();
    try (ToolRegistry tools = tools(drafts)) {
      AgentResult result = subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("创建一个北京旅行计划", new JsonObject(), List.of()),
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

  @Test void reusesPreviousTravelStateWithoutRepeatingQuestions() throws Exception {
    AtomicReference<JsonObject> receivedArguments = new AtomicReference<>();
    JsonObject generated = toPlan("[\"请再告诉我目的地和日期\"]");
    JsonObject generatedRequest = generated.getAsJsonObject("request");
    generatedRequest.addProperty("destination", "");
    generatedRequest.addProperty("origin", "");
    generatedRequest.addProperty("startDate", "");
    generatedRequest.addProperty("endDate", "");
    JsonObject previous = JsonParser.parseString("""
        {"destination":"北京","origin":"杭州","startDate":"2026-09-01","endDate":"2026-09-03",
        "travelers":2,"budget":{"amount":5000,"currency":"CNY"},"pace":"relaxed",
        "interests":["美食"],"constraints":[]}
        """).getAsJsonObject();

    try (ToolRegistry tools = tools(new AtomicInteger())) {
      TravelSubagent subagent = new TravelSubagent((request, sources) -> {
        receivedArguments.set(request.arguments());
        return generated.deepCopy();
      }, tools, new TravelPolicy(), new JsonObject(), new JsonObject());
      AgentResult result = subagent.execute(new SubagentRequest("两个人，预算五千", new JsonObject(), List.of()),
          context(Set.of("travel:read"), previous));

      assertEquals(AgentStatus.COMPLETED, result.status());
      assertTrue(result.data().getAsJsonArray("questions").isEmpty());
      assertEquals("北京", result.data().getAsJsonObject("request").get("destination").getAsString());
      assertEquals("杭州", receivedArguments.get().get("origin").getAsString());
      assertEquals("2026-09-01", receivedArguments.get().get("startDate").getAsString());
    }
  }

  @Test void doesNotHidePermissionFailuresAsResearchDegradation() {
    try (ToolRegistry tools = tools(new AtomicInteger())) {
      assertThrows(SecurityException.class, () -> subagent(toPlan("[]"), tools).execute(
          new SubagentRequest("推荐一个北京三日游", new JsonObject(), List.of()), context(Set.of())));
    }
  }

  private TravelSubagent subagent(JsonObject generated, ToolRegistry tools) {
    return new TravelSubagent((request, sources) -> generated.deepCopy(), tools, new TravelPolicy(),
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
    tools.register(handler(TravelWeatherTool.NAME, Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> {
          JsonObject data = new JsonObject(); data.add("weather", new JsonObject());
          return AgentResult.completed("weather", data, "trace");
        }));
    tools.register(handler(RouteEstimateTool.NAME, Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> {
          JsonObject data = new JsonObject(); data.add("routes", new JsonArray());
          return AgentResult.completed("routes", data, "trace");
        }));
    tools.register(handler(OpeningHoursTool.NAME, Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> {
          JsonObject data = new JsonObject(); data.add("openingHours", new JsonArray());
          return AgentResult.completed("opening", data, "trace");
        }));
    tools.register(handler(TravelPlanValidationTool.NAME, Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
        ToolSideEffect.NONE, false, call -> {
          JsonObject validation = new JsonObject(); validation.add("issues", new JsonArray());
          JsonObject data = new JsonObject(); data.add("validation", validation);
          return AgentResult.completed("validation", data, "trace");
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

  private AgentContext context(Set<String> permissions) {
    return context(permissions, new JsonObject());
  }

  private AgentContext context(Set<String> permissions, JsonObject taskState) {
    UUID runId = UUID.randomUUID();
    return new AgentContext(runId, UUID.randomUUID(), "trace", new Database.Context(UUID.randomUUID(),
        UUID.randomUUID()), "web", permissions, Instant.now().plusSeconds(5), taskState);
  }

  @FunctionalInterface private interface ToolWork { AgentResult execute(ToolCall call) throws Exception; }
}
