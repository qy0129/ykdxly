package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ModelTravelPlannerTest {
  @Test void usesLongFormTravelDefaults() {
    assertEquals(4800, ModelTravelPlanner.DEFAULT_MAX_TOKENS);
    assertEquals(120, ModelTravelPlanner.DEFAULT_TIMEOUT_SECONDS);
  }

  @Test void invalidOrUnsafeConfigurationFallsBack() {
    assertEquals(120, ModelTravelPlanner.boundedInt("not-a-number", 120, 10, 170));
    assertEquals(120, ModelTravelPlanner.boundedInt("180", 120, 10, 170));
    assertEquals(90, ModelTravelPlanner.boundedInt("90", 120, 10, 170));
  }
}
