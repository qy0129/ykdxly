package com.changlu.planner.agent.subagents.diet;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Tool 超时 / 失败 / 有限重试 / 崩溃恢复（设计 §11：DietRecoveryTest）。 */
final class DietRecoveryTest {
  @Test void readOnlyFailureRetriesAtMostConfiguredAttempts() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler("diet.retry", Duration.ofSeconds(1), RetryPolicy.readOnlyNetwork(), () -> {
        if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
        return AgentResult.completed("recovered", new JsonObject(), "trace");
      }));
      AgentResult result = registry.execute(new ToolCall("retry-1", null, "diet.retry", new JsonObject()),
          context());
      assertEquals("recovered", result.message());
      assertEquals(2, attempts.get());
    }
  }

  @Test void toolTimeoutIsReportedAndExecutionIsCancelled() {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler("diet.timeout", Duration.ofMillis(20), RetryPolicy.none(), () -> {
        Thread.sleep(2_000); return AgentResult.completed("late", new JsonObject(), "trace");
      }));
      assertThrows(ToolRegistry.ToolTimeoutException.class, () -> registry.execute(
          new ToolCall("timeout-1", null, "diet.timeout", new JsonObject()), context()));
    }
  }

  @Test void idempotentObserverCanReplayPersistedResultWithoutExecutingTool() throws Exception {
    AtomicInteger executions = new AtomicInteger();
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(handler("diet.replay", Duration.ofSeconds(1), RetryPolicy.none(), () -> {
        executions.incrementAndGet();
        return AgentResult.completed("new", new JsonObject(), "trace");
      }));
      registry.setObserver(new ToolRegistry.Observer() {
        @Override public AgentResult started(ToolCall call, ToolDefinition definition,
                                             AgentContext context, int attempt) {
          return AgentResult.completed("persisted", new JsonObject(), context.traceId());
        }
        @Override public void finished(ToolCall call, ToolDefinition definition, AgentContext context,
                                       int attempt, AgentResult result, Exception error, long durationMs) {}
      });
      AgentResult result = registry.execute(
          new ToolCall("replay-1", "stable-key", "diet.replay", new JsonObject()), context());
      assertEquals("persisted", result.message());
      assertEquals(0, executions.get());
    }
  }

  private ToolHandler handler(String name, Duration timeout, RetryPolicy retry, Work work) {
    return new ToolHandler() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(name, "1.0.0", name, new JsonObject(), new JsonObject(), Set.of(),
            ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false, timeout, retry);
      }
      @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
        return work.run();
      }
    };
  }

  private AgentContext context() {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", Set.of(),
        Instant.now().plusSeconds(5), new JsonObject());
  }

  @FunctionalInterface private interface Work { AgentResult run() throws Exception; }
}
