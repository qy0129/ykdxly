package com.changlu.planner.agent.subagents.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.AgentStatus;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationException;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationProvider;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationService;
import com.changlu.planner.agent.subagents.image.tools.ImageGenerationTool;
import com.changlu.planner.agent.subagents.image.tools.InMemoryImageGenerationRepository;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ImageSubagentTest {
  private enum Behavior { SUCCESS, FAIL_ONCE }

  private Behavior behavior = Behavior.SUCCESS;
  private final AtomicInteger calls = new AtomicInteger();
  private final InMemoryImageGenerationRepository repository = new InMemoryImageGenerationRepository();
  private final ImagePrompt prompt = new ImagePrompt();
  private final ImageGenerationProvider provider = new ImageGenerationProvider() {
    @Override public String name() { return "fake"; }

    @Override public String generate(String p, String size, String style, int quality) throws Exception {
      calls.incrementAndGet();
      if (behavior == Behavior.FAIL_ONCE && calls.get() == 1) {
        throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "boom", true);
      }
      return "https://img.test/" + calls.get() + ".png";
    }
  };

  @Test void definitionIsDiscoverable() {
    ImageSubagent subagent = subagent();
    assertEquals("image.generation", subagent.definition().name());
    assertTrue(subagent.definition().networkAllowed());
    assertTrue(subagent.definition().writeAllowed());
    assertTrue(subagent.definition().allowedTools().contains(ImageGenerationTool.NAME));
    assertTrue(subagent.definition().supportedScenarios().stream()
        .anyMatch(scenario -> scenario.contains("生成图片") || scenario.contains("画一张")));
    assertFalse(subagent.definition().unsupportedScenarios().isEmpty());
  }

  @Test void happyPathGeneratesSingleImage() throws Exception {
    AgentResult result = subagent().execute(
        new SubagentRequest("生成一只边牧的图片", new JsonObject(), List.of()), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals("SUCCESS", result.data().get("status").getAsString());
    assertNotNull(result.data().get("imageUrl"));
    assertEquals(1, calls.get());
    assertEquals(1, repository.size());
  }

  @Test void asksForMoreDetailsWhenSubjectMissing() throws Exception {
    AgentResult result = subagent().execute(
        new SubagentRequest("帮我生成一张图片", new JsonObject(), List.of()), context(imagePermission()));
    assertEquals(AgentStatus.WAITING_USER, result.status());
    assertEquals(0, calls.get());
  }

  @Test void refusesHealthAdvice() throws Exception {
    AgentResult result = subagent().execute(
        new SubagentRequest("帮我诊断一下病情", new JsonObject(), List.of()), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_REFUSED", result.errors().get(0).code());
    assertEquals(0, calls.get());
  }

  @Test void requiresImageGeneratePermission() throws Exception {
    assertThrows(SecurityException.class,
        () -> subagent().execute(new SubagentRequest("生成一只猫", new JsonObject(), List.of()),
            context(Set.of())));
  }

  @Test void batchRequestCreatesConfirmationDraft() throws Exception {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("prompt", "一只猫");
    arguments.addProperty("count", 2);
    AgentResult result = subagent().execute(
        new SubagentRequest("批量生成图片", arguments, List.of()), context(imagePermission()));
    assertEquals(AgentStatus.WAITING_CONFIRMATION, result.status());
    assertTrue(result.requiresConfirmation());
    assertNotNull(result.draftId());
    assertEquals(0, calls.get());
  }

  @Test void confirmedBatchExecutesAllImages() throws Exception {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("prompt", "一只猫");
    arguments.addProperty("count", 2);
    arguments.addProperty("confirmed", true);
    AgentResult result = subagent().execute(
        new SubagentRequest("批量生成图片", arguments, List.of()), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals(2, result.data().getAsJsonArray("images").size());
    assertEquals(2, calls.get());
  }

  @Test void deleteConfirmedRejected() throws Exception {
    JsonObject arguments = new JsonObject();
    arguments.addProperty("prompt", "一只猫");
    arguments.addProperty("action", "delete");
    arguments.addProperty("confirmed", true);
    AgentResult result = subagent().execute(
        new SubagentRequest("删除图片", arguments, List.of()), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("IMAGE_DELETE_UNSUPPORTED", result.errors().get(0).code());
  }

  @Test void retriesTransientFailureThenSucceeds() throws Exception {
    behavior = Behavior.FAIL_ONCE;
    AgentResult result = subagent().execute(
        new SubagentRequest("生成一只边牧", new JsonObject(), List.of()), context(imagePermission()));
    assertEquals(AgentStatus.COMPLETED, result.status());
    assertEquals(2, calls.get());
  }

  @Test void repeatedRunsWithSameContextAreIdempotent() throws Exception {
    ImageSubagent subagent = subagent();
    AgentContext context = context(imagePermission());
    AgentResult first = subagent.execute(new SubagentRequest("生成一只猫", new JsonObject(), List.of()), context);
    AgentResult second = subagent.execute(new SubagentRequest("生成一只猫", new JsonObject(), List.of()), context);
    assertEquals(AgentStatus.COMPLETED, first.status());
    assertEquals(AgentStatus.COMPLETED, second.status());
    assertEquals(1, calls.get());
  }

  @Test void tooLongPromptReturnsInvalidArgument() throws Exception {
    AgentResult result = subagent().execute(
        new SubagentRequest("猫".repeat(1001), new JsonObject(), List.of()), context(imagePermission()));
    assertEquals(AgentStatus.FAILED, result.status());
    assertEquals("INVALID_ARGUMENT", result.errors().get(0).code());
  }

  private ImageSubagent subagent() {
    ToolRegistry tools = new ToolRegistry();
    tools.register(new ImageGenerationTool(
        new ImageGenerationService(provider, repository, prompt), prompt));
    return new ImageSubagent(tools, prompt);
  }

  private Set<String> imagePermission() { return Set.of("image.generate"); }

  private AgentContext context(Set<String> permissions) {
    return new AgentContext(UUID.randomUUID(), UUID.randomUUID(), "trace",
        new Database.Context(UUID.randomUUID(), UUID.randomUUID()), "test", permissions,
        Instant.now().plusSeconds(5), new JsonObject());
  }
}
