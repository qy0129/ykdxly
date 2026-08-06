package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import org.junit.jupiter.api.Test;

final class TravelPromptTest {
  @Test void sharedContextRemainsInsideTheSingleLeadingSystemMessage() {
    JsonArray messages = TravelPrompt.messages("去青岛旅行", "{}", "{}", "用户喜欢海边和晚起");

    assertEquals(2, messages.size());
    assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
    assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    assertTrue(messages.get(0).getAsJsonObject().get("content").getAsString().contains("用户喜欢海边和晚起"));
  }

  @Test void sharedContextIsBoundedForLongTravelRequests() {
    String context = "记".repeat(TravelPrompt.MAX_SHARED_CONTEXT_CHARS + 100);

    JsonArray messages = TravelPrompt.messages("去青岛旅行", "{}", "{}", context);

    String system = messages.get(0).getAsJsonObject().get("content").getAsString();
    assertTrue(system.contains("记".repeat(TravelPrompt.MAX_SHARED_CONTEXT_CHARS)));
    assertTrue(!system.contains("记".repeat(TravelPrompt.MAX_SHARED_CONTEXT_CHARS + 1)));
  }
}
