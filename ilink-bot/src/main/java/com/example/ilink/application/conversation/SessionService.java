package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;

import java.util.List;

/** 新建、查看和切换聊天会话。 */
public final class SessionService {

    private final MySqlStore database;
    private final UserSessionStore sessions;

    public SessionService(MySqlStore database, UserSessionStore sessions) {
        this.database = database;
        this.sessions = sessions;
    }

    public String createNewSession(String userId) {
        return sessions.createNewSession(userId).sessionId();
    }

    public List<MySqlStore.SessionRow> listSessions(String userId) {
        return database.isAvailable() ? database.listUserSessions(userId) : List.of();
    }

    public boolean switchSession(String userId, String sessionId) {
        return sessions.activateSession(userId, sessionId);
    }

    public boolean renameSession(String userId, String sessionId, String title) {
        return database.renameSession(sessionId, userId, title);
    }

    public boolean autoNameSession(String userId, String sessionId, String title) {
        return database.autoRenameSession(sessionId, userId, title);
    }

    public String deleteSession(String userId, String sessionId) {
        String activeSessionId = sessions.getCurrentSession(userId).sessionId();
        if (!database.deleteSession(sessionId, userId)) return null;
        if (!sessionId.equals(activeSessionId)) return activeSessionId;

        List<MySqlStore.SessionRow> remaining = database.listUserSessions(userId);
        if (!remaining.isEmpty() && sessions.activateSession(userId, remaining.getFirst().sessionId())) {
            return remaining.getFirst().sessionId();
        }
        return sessions.createNewSession(userId).sessionId();
    }
}
