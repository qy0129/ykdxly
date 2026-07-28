package com.example.ilink.application.conversation;

import com.example.ilink.application.messaging.IncomingMessage;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSessionTest {

    @Test
    void storesBasicInformation() {
        var now = LocalDateTime.now();
        var session = new ConversationSession(
                "user123", "session-001", now, now, List.of(), Map.of());

        assertEquals("user123", session.userId());
        assertEquals("session-001", session.sessionId());
        assertEquals(now, session.createdAt());
        assertEquals(now, session.lastActiveAt());
    }

    @Test
    void providesImmutableMessageList() {
        var now = LocalDateTime.now();
        var mutable = new ArrayList<IncomingMessage>();
        var session = new ConversationSession("u1", "s1", now, now, mutable, Map.of());

        mutable.add(null);

        assertTrue(session.messages().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> session.messages().add(null));
    }

    @Test
    void providesImmutableTaskState() {
        var now = LocalDateTime.now();
        var mutable = new HashMap<String, Object>();
        mutable.put("key", "value");
        var session = new ConversationSession("u1", "s1", now, now, List.of(), mutable);

        mutable.put("another", "v");

        assertEquals(1, session.taskState().size());
        assertThrows(UnsupportedOperationException.class, () -> session.taskState().put("x", "y"));
    }

    @Test
    void defaultsNullCollectionsToEmpty() {
        var now = LocalDateTime.now();
        var session = new ConversationSession("u1", "s1", now, now, null, null);

        assertTrue(session.messages().isEmpty());
        assertTrue(session.taskState().isEmpty());
    }

    @Test
    void rejectsNullRequiredFields() {
        var now = LocalDateTime.now();

        assertThrows(NullPointerException.class,
                () -> new ConversationSession(null, "s1", now, now, List.of(), Map.of()));
        assertThrows(NullPointerException.class,
                () -> new ConversationSession("u1", null, now, now, List.of(), Map.of()));
        assertThrows(NullPointerException.class,
                () -> new ConversationSession("u1", "s1", null, now, List.of(), Map.of()));
        assertThrows(NullPointerException.class,
                () -> new ConversationSession("u1", "s1", now, null, List.of(), Map.of()));
    }
}
