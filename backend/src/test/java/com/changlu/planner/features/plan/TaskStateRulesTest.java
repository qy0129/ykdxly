package com.changlu.planner.features.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class TaskStateRulesTest {
  @Test void completingAndReopeningAreValid() {
    assertDoesNotThrow(() -> TaskStateRules.validate("pending", "done", "complete_task", null, null));
    assertDoesNotThrow(() -> TaskStateRules.validate("done", "pending", "reopen_task", null, null));
  }

  @Test void blockedTaskRequiresReason() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TaskStateRules.validate("pending", "blocked", "block_task", "", null));
    assertEquals("blocked_reason_required", error.getMessage());
  }

  @Test void delayedTaskRequiresNewDueTime() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TaskStateRules.validate("pending", "pending", "delay_task", null, null));
    assertEquals("dueAt_required", error.getMessage());
  }

  @Test void terminalTaskMustBeReopenedBeforeAnotherTerminalState() {
    IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
        () -> TaskStateRules.validate("done", "cancelled", "cancel_task", null, null));
    assertEquals("terminal_task_must_reopen_first", error.getMessage());
  }
}
