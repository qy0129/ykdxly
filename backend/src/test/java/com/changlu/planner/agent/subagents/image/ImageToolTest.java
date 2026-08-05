package com.changlu.planner.agent.subagents.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationException;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationProvider;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationService;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationTool;
import com.changlu.planner.agent.subagents.image.tools.InMemoryImageGenerationRepository;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ImageToolTest {
  private enum Behavior { SUCCESS, RETRYABLE_FAIL, FAIL_ONCE, AUTH_FAIL }

  private Behavior behavior = Behavior.SUCCESS;
  private final AtomicInteger calls = new AtomicInteger();
  private final InMemoryImageGenerationRepository repository = new InMemoryImageGenerationRepository();
  private final ImagePrompt prompt = new ImagePrompt();

  private final ImageGenerationProvider provider = new ImageGenerationProvider() {
    @Override public String name() { return "fake"; }

    @Override public String generate(String p, String size, String style, int quality) throws Exception {
      calls.incrementAndGet();
      switch (behavior) {
        case SUCCESS:
          return "https://img.test/" + calls.get() + ".png";
        case RETRYABLE_FAIL:
          throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "provider exploded secret-token-123", true);
        case AUTH_FAIL:
          throw new ImageGenerationException("AUTH_FAILED", "401 invalid api key", false);
        case FAIL_ONCE:
          if (calls.get() == 1) throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "boom", true);
          return "https://img.test/" + calls.get() + ".png";
        default:
          throw new IllegalStateException("unreachable");
      }
    }
  };

  @Test void exposesExpectedMetadata() {
    ToolDefinition definition = tool().definition();
    assertEquals("image.generate", definition.name());
    assertEquals(ToolRiskLevel.HIGH_RISK_WRITE, definition.riskLevel());
    assertEquals(ToolSideEffect.EXTERNAL_WRITE, definition.sideEffect());
    assertFalse(definition.requiresConfirmation());
    assertEquals(Duration.ofSeconds(120), definition.timeout());
    assertTrue(definition.requiredPermissions().contains("image.generate"));
    assertTrue(definition.inputSchema().has("properties"));
    assertTrue(definition.outputSchema().has("properties"));
  }

  @Test void missingPromptRejected() {
    AgentResult result = tool().execute(call("k-1", args()), context(imagePermission()));
    assertEquals(AgentStatus.WAITING_USER, result.status());
    assertFalse(result.message().isBlank());
  }

  @Test void messageIsAcceptedAsPromptForDirectToolCalls() {
    AgentResult result = tool().execute(call("k-1", args("message", "帮我画一只猫")), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertNotNull(result.data().get("imageUrl"));
  }

  @Test void nestedArgumentsAreAcceptedForDirectToolCalls() {
    JsonObject envelope = new JsonObject();
    envelope.add("arguments", args("prompt", "一只猫"));
    AgentResult result = tool().execute(call("k-1", envelope), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertNotNull(result.data().get("imageUrl"));
  }

  @Test void invalidSizeRejected() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "size", "999x999")), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("INVALID_ARGUMENT", result.errors().get(0).code());
  }

  @Test void invalidStyleRejected() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "style", "fancy")), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("INVALID_ARGUMENT", result.errors().get(0).code());
  }

  @Test void qualityOutOfRangeRejected() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "quality", 9)), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("INVALID_ARGUMENT", result.errors().get(0).code());
  }

  @Test void tooLongPromptRejected() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "猫".repeat(1001))), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("INVALID_ARGUMENT", result.errors().get(0).code());
  }

  @Test void singleGenerationSucceeds() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只边牧")), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals("SUCCESS", result.data().get("status").getAsString());
    assertNotNull(result.data().get("imageUrl"));
    assertTrue(result.data().get("imageUrl").getAsString().startsWith("https://img.test/"));
  }

  @Test void transientFailureRetriedOnceThenSucceeds() {
    behavior = Behavior.FAIL_ONCE;
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只边牧")), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals(2, calls.get());
  }

  @Test void retryExhaustedMapsToReadableUnavailable() {
    behavior = Behavior.RETRYABLE_FAIL;
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只边牧")), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_SERVICE_UNAVAILABLE", result.errors().get(0).code());
    assertEquals(2, calls.get());
  }

  @Test void authFailureMapsToReadableMessage() {
    behavior = Behavior.AUTH_FAIL;
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只边牧")), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_AUTH_FAILED", result.errors().get(0).code());
  }

  @Test void sensitiveProviderDetailIsNotLeakedToUser() {
    behavior = Behavior.RETRYABLE_FAIL;
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只边牧")), context(imagePermission()));
    assertFalse(result.message().contains("secret-token-123"));
  }

  @Test void batchWithoutConfirmationReturnsDraft() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "mode", "batch")), context(imagePermission()));
    assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
    assertTrue(result.requiresConfirmation());
    assertNotNull(result.draftId());
    assertEquals(0, calls.get());
  }

  @Test void confirmedBatchGeneratesAllImages() {
    AgentResult result = tool().execute(
        call("k-1", args("prompt", "一只猫", "count", 2, "confirmed", true)), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals(2, result.data().getAsJsonArray("images").size());
    assertEquals(2, calls.get());
  }

  @Test void deleteConfirmedRejected() {
    AgentResult result = tool().execute(
        call("k-1", args("prompt", "一只猫", "action", "delete", "confirmed", true)), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_DELETE_UNSUPPORTED", result.errors().get(0).code());
  }

  @Test void deleteWithoutConfirmationReturnsDraft() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "action", "delete")), context(imagePermission()));
    assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
  }

  @Test void updateRejected() {
    AgentResult result = tool().execute(call("k-1", args("prompt", "一只猫", "action", "update")), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_UPDATE_UNSUPPORTED", result.errors().get(0).code());
  }

  @Test void permissionRequiredBeforeExecution() throws Exception {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(tool());
      assertThrows(SecurityException.class,
          () -> registry.execute(call("k-1", args("prompt", "一只猫")), context(Set.of())));
      assertEquals(0, calls.get());
    }
  }

  @Test void sameIdempotencyKeyDeduplicates() {
    AgentResult first = tool().execute(call("dup-key", args("prompt", "一只猫")), context(imagePermission()));
    AgentResult second = tool().execute(call("dup-key", args("prompt", "一只猫")), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, first.status());
    assertEquals(AgentStatus.COMPLETED, second.status());
    assertEquals(1, calls.get());
    assertEquals(first.data().get("requestId").getAsString(),
        second.data().get("requestId").getAsString());
  }

  @Test void toolTimeoutThrowsTimeoutException() throws Exception {
    try (ToolRegistry registry = new ToolRegistry()) {
      registry.register(new ToolHandler() {
        @Override public ToolDefinition definition() {
          return new ToolDefinition("slow.test", "1.0.0", "slow", new JsonObject(), new JsonObject(),
              Set.of(), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE, false,
              Duration.ofMillis(100), RetryPolicy.none());
        }
        @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
          Thread.sleep(2_000);
          return AgentResult.completed("late", new JsonObject(), context.traceId());
        }
      });
      assertThrows(ToolRegistry.ToolTimeoutException.class,
          () -> registry.execute(new ToolCall("call-t", "k", "slow.test", new JsonObject()),
              context(Set.of())));
    }
  }

  private ImageGenerationService service() {
    return new ImageGenerationService(provider, repository, prompt);
  }

  private ImageGenerationTool tool() { return new ImageGenerationTool(service(), prompt); }

  private ToolCall call(String key, JsonObject arguments) {
    return new ToolCall("call-1", key, ImageGenerationTool.NAME, arguments);
  }

  private JsonObject args() { return new JsonObject(); }

  private JsonObject args(String key, String value) {
    JsonObject args = new JsonObject();
    args.addProperty(key, value);
    return args;
  }

  private JsonObject args(String key, String value, String key2, String value2) {
    JsonObject args = new JsonObject();
    args.addProperty(key, value);
    args.addProperty(key2, value2);
    return args;
  }

  private JsonObject args(String key, String value, String key2, int value2) {
    JsonObject args = new JsonObject();
    args.addProperty(key, value);
    args.addProperty(key2, value2);
    return args;
  }

  private JsonObject args(String key, String value, String key2, String value2, String key3, boolean value3) {
    JsonObject args = new JsonObject();
    args.addProperty(key, value);
    args.addProperty(key2, value2);
    args.addProperty(key3, value3);
    return args;
  }

  private JsonObject args(String key, String value, String key2, int value2, String key3, boolean value3) {
    JsonObject args = new JsonObject();
    args.addProperty(key, value);
    args.addProperty(key2, value2);
    args.addProperty(key3, value3);
    return args;
  }

  private Set<String> imagePermission() { return Set.of("image.generate"); }

  private AgentContext context(Set<String> permissions) {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", permissions,
        Instant.now().plusSeconds(5), new JsonObject());
  }
}
