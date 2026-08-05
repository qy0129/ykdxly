package com.changlu.planner.agent.core.tool;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Standard Tool boundary: registration, permission, risk, retry and idempotency checks. */
public final class ToolRegistry implements AutoCloseable {
  public interface Observer {
    AgentResult started(ToolCall call, ToolDefinition definition, AgentContext context, int attempt) throws Exception;
    void finished(ToolCall call, ToolDefinition definition, AgentContext context, int attempt,
                  AgentResult result, Exception error, long durationMs) throws Exception;
  }

  private static final Observer NOOP_OBSERVER = new Observer() {
    @Override public AgentResult started(ToolCall call, ToolDefinition definition,
                                         AgentContext context, int attempt) { return null; }
    @Override public void finished(ToolCall call, ToolDefinition definition, AgentContext context, int attempt,
                                   AgentResult result, Exception error, long durationMs) {}
  };
  private final Map<String, ToolHandler> handlers = new LinkedHashMap<>();
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private Observer observer = NOOP_OBSERVER;

  public void setObserver(Observer observer) { this.observer = observer == null ? NOOP_OBSERVER : observer; }

  public void register(ToolHandler handler) {
    String name = handler.definition().name();
    if (handlers.putIfAbsent(name, handler) != null) throw new IllegalArgumentException("工具重复注册：" + name);
  }

  public ToolHandler require(String name) {
    ToolHandler value = handlers.get(name);
    if (value == null) throw new IllegalArgumentException("工具未注册：" + name);
    return value;
  }

  public boolean contains(String name) { return handlers.containsKey(name); }

  public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    ToolHandler handler = require(call.toolName());
    ToolDefinition definition = handler.definition();
    for (String permission : definition.requiredPermissions()) {
      if (!context.hasPermission(permission)) throw new SecurityException("PERMISSION_DENIED:" + permission);
    }
    if (definition.riskLevel() == ToolRiskLevel.RESTRICTED) {
      throw new SecurityException("RESTRICTED_TOOL_DENIED:" + definition.name());
    }
    if (definition.sideEffect() != ToolSideEffect.NONE
        && definition.retryPolicy().maxAttempts() > 1
        && (call.idempotencyKey() == null || call.idempotencyKey().isBlank())) {
      throw new IllegalArgumentException("idempotency_key_required");
    }
    Exception last = null;
    for (int attempt = 1; attempt <= definition.retryPolicy().maxAttempts(); attempt++) {
      long startedAt = System.nanoTime();
      AgentResult replay = observer.started(call, definition, context, attempt);
      if (replay != null) return replay;
      try {
        AgentResult result = executeOnce(handler, call, context, definition);
        observer.finished(call, definition, context, attempt, result, null, elapsedMs(startedAt));
        return result;
      }
      catch (SecurityException | IllegalArgumentException error) {
        observer.finished(call, definition, context, attempt, null, error, elapsedMs(startedAt));
        throw error;
      }
      catch (Exception error) {
        observer.finished(call, definition, context, attempt, null, error, elapsedMs(startedAt));
        last = error;
        if (attempt == definition.retryPolicy().maxAttempts()) throw error;
        long delay = Math.round(definition.retryPolicy().initialDelay().toMillis()
            * Math.pow(definition.retryPolicy().backoffMultiplier(), attempt - 1));
        if (delay > 0) Thread.sleep(delay);
      }
    }
    throw last;
  }

  private long elapsedMs(long startedAt) { return (System.nanoTime() - startedAt) / 1_000_000; }

  private AgentResult executeOnce(ToolHandler handler, ToolCall call, AgentContext context,
                                  ToolDefinition definition) throws Exception {
    Future<AgentResult> future = executor.submit(() -> handler.execute(call, context));
    try {
      return future.get(definition.timeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException error) {
      future.cancel(true);
      throw new ToolTimeoutException(definition.name(), error);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof Exception exception) throw exception;
      throw new IllegalStateException("tool_execution_failed", cause);
    } catch (InterruptedException error) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw error;
    }
  }

  public JsonArray definitions() {
    JsonArray rows = new JsonArray();
    for (ToolHandler handler : handlers.values()) {
      ToolDefinition definition = handler.definition();
      JsonObject row = new JsonObject();
      row.addProperty("name", definition.name());
      row.addProperty("version", definition.version());
      row.addProperty("description", definition.description());
      row.addProperty("riskLevel", definition.riskLevel().name());
      row.addProperty("requiresConfirmation", definition.requiresConfirmation());
      rows.add(row);
    }
    return rows;
  }

  @Override public void close() { executor.shutdownNow(); }

  public static final class ToolTimeoutException extends Exception {
    public ToolTimeoutException(String toolName, Throwable cause) {
      super("TOOL_TIMEOUT:" + toolName, cause);
    }
  }
}
