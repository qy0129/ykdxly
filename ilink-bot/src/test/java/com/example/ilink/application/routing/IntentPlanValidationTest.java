package com.example.ilink.application.routing;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntentPlanValidationTest {

    @Test
    void ordersActionsAfterTheirDependencies() {
        IntentAction second = action("r2", List.of("r1"));
        IntentAction first = action("r1", List.of());

        IntentPlan plan = new IntentPlan(List.of(second, first));

        assertEquals(List.of("r1", "r2"),
                plan.actions().stream().map(IntentAction::requirementId).toList());
    }

    @Test
    void rejectsUnknownDependency() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlan(List.of(action("r1", List.of("missing")))));
    }

    @Test
    void rejectsSelfAndCircularDependencies() {
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlan(List.of(action("r1", List.of("r1")))));
        assertThrows(IllegalArgumentException.class,
                () -> new IntentPlan(List.of(
                        action("r1", List.of("r2")),
                        action("r2", List.of("r1")))));
    }

    @Test
    void assignsIdsToCompatibilityActions() {
        IntentPlan plan = new IntentPlan(List.of(
                new IntentAction("第一项", IntentResult.chat()),
                new IntentAction("第二项", IntentResult.chat())));

        assertEquals(List.of("r1", "r2"),
                plan.actions().stream().map(IntentAction::requirementId).toList());
    }

    private static IntentAction action(String id, List<String> dependencies) {
        return new IntentAction(id, id, dependencies, IntentResult.chat());
    }
}
