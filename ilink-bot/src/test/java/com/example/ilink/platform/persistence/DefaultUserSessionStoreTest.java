package com.example.ilink.platform.persistence;

import org.junit.jupiter.api.Test;

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
}
