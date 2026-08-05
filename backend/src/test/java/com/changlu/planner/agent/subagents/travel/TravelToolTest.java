package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class TravelToolTest {
  @Test void permissionIsRequiredBeforeExecution() {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler(Set.of("travel:read"), ToolRiskLevel.READ_ONLY,
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

  private ToolHandler handler(Set<String> permissions, ToolRiskLevel risk, ToolSideEffect sideEffect,
                              RetryPolicy retry) {
    return new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition("travel.test", "1.0.0", "test", new JsonObject(), new JsonObject(), permissions,
            risk, sideEffect, risk != ToolRiskLevel.READ_ONLY, Duration.ofSeconds(1), retry);
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) {
        return AgentResult.completed("ok", new JsonObject(), context.traceId());
      }
    };
  }

  private ToolCall call(String idempotencyKey) {
    return new ToolCall("call-1", idempotencyKey, "travel.test", new JsonObject());
  }

  private AgentContext context(Set<String> permissions) {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", permissions,
        Instant.now().plusSeconds(5), new JsonObject());
  }
}
