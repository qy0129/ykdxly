package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;

import java.util.List;

public final class SessionService {

    private final MySqlStore database;
    private final UserSessionStore sessions;

    public SessionService(MySqlStore database, UserSessionStore sessions) {
        this.database = database;
        this.sessions = sessions;
    }

    public String createNewSession(String userId) {
        ConversationSession session = sessions.createNewSession(userId);
        if (database.isAvailable()) {
            database.deactivateOtherSessions(session.sessionId(), userId);
        }
        return session.sessionId();
    }

    public List<MySqlStore.SessionRow> listSessions(String userId) {
        if (!database.isAvailable()) return List.of();
        return database.listUserSessions(userId);
    }

    public void switchSession(String userId, String sessionId) {
        if (!database.isAvailable()) return;
        database.switchActiveSession(sessionId, userId);
    }

    public void closeSession(String sessionId) {
        if (!database.isAvailable()) return;
        database.closeSession(sessionId);
    }
}
