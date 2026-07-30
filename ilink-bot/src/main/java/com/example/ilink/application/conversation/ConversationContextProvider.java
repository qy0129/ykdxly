package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;

public final class ConversationContextProvider {
    private final UserSessionStore sessions;
    private final MySqlStore database;

    public ConversationContextProvider(UserSessionStore sessions) {
        this.sessions = sessions;
        this.database = MySqlStore.getInstance();
    }

    public ConversationContext build(String userId) {
        ConversationSession session = sessions.getCurrentSession(userId);
        return build(userId, session.sessionId());
    }

    public ConversationContext build(String userId, String requestedSessionId) {
        String sessionId = requestedSessionId == null || requestedSessionId.isBlank()
                ? sessions.getCurrentSession(userId).sessionId() : requestedSessionId;
        return new ConversationContext(sessionId, sessions.getPersonaPrompt(userId),
                database.loadSessionSummary(sessionId),
                database.loadSessionMessages(sessionId, 20));
    }
}
