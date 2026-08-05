package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
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
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tool 契约：风险级别、副作用、确认、超时与重试（设计 §7 / §11：DietToolTest）。 */
final class DietToolTest {
  @Test void nutritionReferenceToolDeclaresDesignContract() {
    ToolDefinition definition = new NutritionReferenceTool(null).definition();
    assertEquals("diet.nutrition.reference", definition.name());
    assertTrue(definition.requiredPermissions().contains("diet:read"));
    assertEquals(ToolRiskLevel.READ_ONLY, definition.riskLevel());
    assertEquals(ToolSideEffect.NONE, definition.sideEffect());
    assertFalse(definition.requiresConfirmation());
    assertEquals(Duration.ofSeconds(20), definition.timeout());
    assertEquals(2, definition.retryPolicy().maxAttempts());
  }

  @Test void dietDraftToolDeclaresDesignContract() {
    ToolDefinition definition = new DietDraftTool(null).definition();
    assertEquals("diet.plan.draft", definition.name());
    assertTrue(definition.requiredPermissions().contains("planning:write"));
    assertEquals(ToolRiskLevel.LOW_RISK_WRITE, definition.riskLevel());
    assertEquals(ToolSideEffect.INTERNAL_WRITE, definition.sideEffect());
    assertTrue(definition.requiresConfirmation());
    assertEquals(Duration.ofSeconds(90), definition.timeout());
    assertEquals(1, definition.retryPolicy().maxAttempts());
  }

  @Test void permissionIsRequiredBeforeExecution() {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler(Set.of("diet:read"), ToolRiskLevel.READ_ONLY,
          ToolSideEffect.NONE, RetryPolicy.none()));
      assertThrows(SecurityException.class, () -> registry.execute(call(null), context(Set.of())));
    }
  }

  @Test void retriedWriteRequiresIdempotencyKey() {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler(Set.of(), ToolRiskLevel.LOW_RISK_WRITE, ToolSideEffect.INTERNAL_WRITE,
          new RetryPolicy(2, Duration.ZERO, 1)));
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
          () -> registry.execute(call(null), context(Set.of())));
      assertEquals("idempotency_key_required", error.getMessage());
    }
  }

  @Test void draftToolRejectsMissingIdempotencyKeyBeforeServiceCall() {
    // 幂等键在触碰 AiCommandService 之前就强制（设计 §7.2）
    JsonObject arguments = new JsonObject();
    arguments.addProperty("planningInstruction", "创建四周健康饮食计划");
    ToolCall call = new ToolCall("call-1", null, DietDraftTool.NAME, arguments);
    assertThrows(IllegalArgumentException.class,
        () -> new DietDraftTool(null).execute(call, context(Set.of("planning:write"))));
  }

  @Test void researchToolRejectsBlankQueryBeforeNetwork() {
    ToolCall call = new ToolCall("call-1", null, NutritionReferenceTool.NAME, new JsonObject());
    assertThrows(IllegalArgumentException.class,
        () -> new NutritionReferenceTool(null).execute(call, context(Set.of("diet:read"))));
  }

  private ToolHandler handler(Set<String> permissions, ToolRiskLevel risk, ToolSideEffect sideEffect,
                              RetryPolicy retry) {
    return new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition("diet.test", "1.0.0", "test", new JsonObject(), new JsonObject(), permissions,
            risk, sideEffect, risk != ToolRiskLevel.READ_ONLY, Duration.ofSeconds(1), retry);
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    };
  }

  private ToolCall call(String idempotencyKey) {
    return new ToolCall("call-1", idempotencyKey, "diet.test", new JsonObject());
  }

  private AgentContext context(Set<String> permissions) {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", permissions,
        Instant.now().plusSeconds(5), new JsonObject());
  }
}
