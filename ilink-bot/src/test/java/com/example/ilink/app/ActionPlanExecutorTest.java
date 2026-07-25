package com.example.ilink.app;

import com.example.ilink.routing.IntentAction;
import com.example.ilink.routing.IntentPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionPlanExecutorTest {

    @Test
    void pausesAndResumesRemainingActionsInOrder() throws Exception {
        ActionPlanExecutor executor = new ActionPlanExecutor();
        List<String> executed = new ArrayList<>();
        AtomicBoolean pending = new AtomicBoolean(false);
        IntentPlan plan = new IntentPlan(List.of(action("route"), action("weather")));

        executor.start("user", plan, action -> {
            executed.add(action.requestText());
            pending.set("route".equals(action.requestText()));
        }, pending::get, (action, error) -> { throw error; });

        assertEquals(List.of("route"), executed);
        pending.set(false);
        executor.resume("user", action -> executed.add(action.requestText()), pending::get,
                (action, error) -> { throw error; });

        assertEquals(List.of("route", "weather"), executed);
    }

    @Test
    void preservesFailedActionForExplicitRetry() throws Exception {
        ActionPlanExecutor executor = new ActionPlanExecutor();
        List<String> executed = new ArrayList<>();
        AtomicBoolean failFirst = new AtomicBoolean(true);
        IntentPlan plan = new IntentPlan(List.of(action("weather"), action("news")));

        executor.start("user", plan, action -> {
            executed.add(action.requestText());
            if ("weather".equals(action.requestText()) && failFirst.getAndSet(false)) {
                throw new IllegalStateException("temporary failure");
            }
        }, () -> false, (action, error) -> { });

        assertEquals(List.of("weather", "news"), executed);
        assertTrue(executor.hasFailedAction("user"));
        executor.retryFailed("user", action -> executed.add(action.requestText()), (action, error) -> { });

        assertEquals(List.of("weather", "news", "weather"), executed);
    }

    private IntentAction action(String text) {
        return new IntentAction(text, null);
    }
}
