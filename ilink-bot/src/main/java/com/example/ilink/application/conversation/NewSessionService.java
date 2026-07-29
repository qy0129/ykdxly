package com.example.ilink.application.conversation;

/** 保留给现有启动装配使用的新会话入口。 */
public final class NewSessionService {
    private final UserSessionStore sessions;

    public NewSessionService(UserSessionStore sessions) {
        this.sessions = sessions;
    }

    public String createNewSession(String userId) {
        return sessions.createNewSession(userId).sessionId();
    }
}
