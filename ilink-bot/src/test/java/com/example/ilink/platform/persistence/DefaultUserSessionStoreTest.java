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

    @Test
    void keepsExtractedTodoCandidatesOnlyInsideCurrentSession() {
        DefaultUserSessionStore store = new DefaultUserSessionStore();
        String userId = "todo-candidate-session-test-user";

        store.setPendingTodoCandidates(userId, List.of("修复 Bug", "准备部署环境"));

        assertEquals(List.of("修复 Bug", "准备部署环境"), store.getPendingTodoCandidates(userId));
        store.createNewSession(userId);
        assertEquals(List.of(), store.getPendingTodoCandidates(userId));
    }
}
