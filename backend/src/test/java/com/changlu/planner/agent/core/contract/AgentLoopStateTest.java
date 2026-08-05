package com.changlu.planner.agent.core.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

final class AgentLoopStateTest {
  @Test void roundTripPreservesAllFields() {
    AgentLoopState state = new AgentLoopState();
    state.goal = "规划周末旅行";
    state.userTurns.add("规划周末旅行");
    state.userTurns.add("带上孩子");
    state.appendStep("subagent", "travel", "旅行规划", "COMPLETED", "已生成 3 天行程草案");
    state.appendStep("tool", "planning.assistant", "计划管理", "COMPLETED", "已创建计划");
    state.taskData.addProperty("destination", "莫干山");
    state.iteration = 2;
    state.pendingDraftId = "draft-1";
    state.pendingQuestions.add("住宿偏好是什么？");
    state.confirmedDrafts.add("draft-1");
    state.deadlineEpochMs = 1_800_000_000_000L;

    AgentLoopState restored = AgentLoopState.fromJson(
        JsonParser.parseString(state.toJson().toString()).getAsJsonObject());

    assertEquals("规划周末旅行", restored.goal);
    assertEquals(2, restored.userTurns.size());
    assertEquals(2, restored.steps.size());
    assertEquals("subagent", restored.steps.get(0).getAsJsonObject().get("executorType").getAsString());
    assertEquals("莫干山", restored.taskData.get("destination").getAsString());
    assertEquals(2, restored.iteration);
    assertEquals("draft-1", restored.pendingDraftId);
    assertEquals(1, restored.pendingQuestions.size());
    assertEquals("draft-1", restored.confirmedDrafts.get(0));
    assertEquals(1_800_000_000_000L, restored.deadlineEpochMs);
  }

  @Test void appendStepCompactsLongMessages() {
    AgentLoopState state = new AgentLoopState();
    String longMessage = "很长的消息".repeat(100);
    state.appendStep("tool", "planning.assistant", "计划管理", "COMPLETED", longMessage);
    String stored = state.steps.get(0).getAsJsonObject().get("message").getAsString();
    assertTrue(stored.length() <= 180);
    assertTrue(stored.endsWith("..."));
  }

  @Test void clearPendingQuestionsRemovesAll() {
    AgentLoopState state = new AgentLoopState();
    state.pendingQuestions.add("问题一");
    state.pendingQuestions.add("问题二");
    state.clearPendingQuestions();
    assertEquals(0, state.pendingQuestions.size());
  }

  @Test void emptyStateSerializesAndRestores() {
    AgentLoopState state = new AgentLoopState();
    AgentLoopState restored = AgentLoopState.fromJson(
        JsonParser.parseString(state.toJson().toString()).getAsJsonObject());
    assertTrue(restored.steps.isEmpty());
    assertTrue(restored.userTurns.isEmpty());
    assertNull(restored.pendingDraftId);
    assertEquals(0, restored.deadlineEpochMs);
  }

  @Test void fromNullJsonReturnsEmptyState() {
    AgentLoopState restored = AgentLoopState.fromJson(null);
    assertEquals("", restored.goal);
    assertTrue(restored.steps.isEmpty());
    assertFalse(restored.pastDeadline());
  }
}
