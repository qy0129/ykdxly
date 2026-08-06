package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class ModelTravelPlannerTest {
  @Test void usesLongFormTravelDefaults() {
    assertEquals(4800, ModelTravelPlanner.DEFAULT_MAX_TOKENS);
    // 与 subagent 180s 总 deadline 对齐：collect+模型+路线 < 180s
    assertEquals(90, ModelTravelPlanner.DEFAULT_TIMEOUT_SECONDS);
  }

  @Test void invalidOrUnsafeConfigurationFallsBack() {
    assertEquals(90, ModelTravelPlanner.boundedInt("not-a-number", 90, 10, 110));
    assertEquals(90, ModelTravelPlanner.boundedInt("180", 90, 10, 110)); // 超 110 上限 → 回退
    assertEquals(90, ModelTravelPlanner.boundedInt("5", 90, 10, 110));   // 低于 10 下限 → 回退
    assertEquals(110, ModelTravelPlanner.boundedInt("110", 90, 10, 110));
  }
}
