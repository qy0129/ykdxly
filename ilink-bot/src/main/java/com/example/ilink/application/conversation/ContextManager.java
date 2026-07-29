package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.platform.persistence.MySqlStore;

import java.util.List;

/**
 * 统一触发用户级上下文加载。
 * ChatService 和路由层各自按需读取这些资料，避免把旧会话文本混入新会话。
 */
public final class ContextManager {

    private final UserSessionStore sessions;
    private final MemoryService memoryService;
    private final MySqlStore database;

    public ContextManager(UserSessionStore sessions, MemoryService memoryService) {
        this.sessions = sessions;
        this.memoryService = memoryService;
        this.database = MySqlStore.getInstance();
    }

    public ContextData buildContext(String userId) {
        ConversationSession session = sessions.getCurrentSession(userId);
        String sessionId = session.sessionId();
        return new ContextData(sessionId, sessions.getPersonaPrompt(userId), memoryService.prompt(userId),
                database.loadSessionSummary(sessionId), database.loadSessionMessages(sessionId, 20));
    }

    public record ContextData(String sessionId, String persona, String memories, String summary,
                              List<MySqlStore.ChatEntry> recentMessages) { }
}
