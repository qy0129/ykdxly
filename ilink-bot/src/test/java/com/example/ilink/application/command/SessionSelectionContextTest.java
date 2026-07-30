package com.example.ilink.application.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSelectionContextTest {

    @Test
    void businessInteractionTakesPriorityAndConsumesSessionSelection() {
        SessionSelectionContext context = new SessionSelectionContext();
        context.open("user", "session-a");

        assertFalse(context.permitsBareNumber("user", "session-a", true));
        assertFalse(context.permitsBareNumber("user", "session-a", false));
    }

    @Test
    void selectionIsBoundToSourceSessionAndExpires() {
        AtomicLong now = new AtomicLong(1_000L);
        SessionSelectionContext context = new SessionSelectionContext(now::get, 500L);
        context.open("user", "session-a");

        assertTrue(context.permitsBareNumber("user", "session-a", false));
        assertFalse(context.permitsBareNumber("user", "session-b", false));

        context.open("user", "session-a");
        now.set(1_500L);
        assertFalse(context.permitsBareNumber("user", "session-a", false));
    }
}
