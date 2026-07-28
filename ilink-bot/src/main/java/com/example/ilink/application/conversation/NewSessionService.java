package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;

/**
 * 创建新聊天会话的业务服务。
 *
 * <p>负责"新聊天"的完整业务流程：
 * <ol>
 *   <li>停用当前用户的 ACTIVE 旧会话</li>
 *   <li>创建新会话</li>
 * </ol>
 * </p>
 *
 * <p>不直接操作数据库，通过 {@link UserSessionStore} 进行数据存取。</p>
 */
public final class NewSessionService {

    private final UserSessionStore sessions;

    public NewSessionService(UserSessionStore sessions) {
        this.sessions = sessions;
    }

    /**
     * 为用户创建一个新聊天会话。
     *
     * <p>如果用户当前有 ACTIVE 状态的会话，先将其置为 INACTIVE，
     * 再创建新会话。</p>
     *
     * @param userId 微信用户 ID
     * @return 新创建的 sessionId
     */
    public String createNewSession(String userId) {
        ConversationSession session = sessions.createNewSession(userId);
        MySqlStore database = MySqlStore.getInstance();
        if (database.isAvailable()) {
            database.deactivateOtherSessions(session.sessionId(), userId);
        }
        return session.sessionId();
    }
}
