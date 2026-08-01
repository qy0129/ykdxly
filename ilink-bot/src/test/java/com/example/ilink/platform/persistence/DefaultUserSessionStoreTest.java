package com.example.ilink.platform.persistence;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultUserSessionStoreTest {

    @Test
    void newSessionKeepsUserProfileButDropsTemporaryState() {
        DefaultUserSessionStore store = new DefaultUserSessionStore();
        String userId = "session-test-user";

        String firstSession = store.getCurrentSession(userId).sessionId();
        store.setCurrentLocation(userId, "杭州市西湖区");
        store.setPendingDraw(userId, "a landscape");

        String secondSession = store.createNewSession(userId).sessionId();

        assertNotEquals(firstSession, secondSession);
        assertEquals("杭州市西湖区", store.getCurrentLocation(userId));
        assertNull(store.getPendingDraw(userId));
    }

    @Test
    void keepsRecentTodoBatchInsideCurrentSession() {
        DefaultUserSessionStore store = new DefaultUserSessionStore();
        String userId = "recent-todo-session-test-user";

        store.setLastCreatedTodoIds(userId, List.of("todo-1", "todo-2"));

        assertEquals(List.of("todo-1", "todo-2"), store.getLastCreatedTodoIds(userId));
        store.createNewSession(userId);
        assertEquals(List.of(), store.getLastCreatedTodoIds(userId));
    }
}
