package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.memory.MemoryService;

/**
 * 统一触发用户级上下文加载。
 * ChatService 和路由层各自按需读取这些资料，避免把旧会话文本混入新会话。
 */
public final class ContextManager {

    private final UserSessionStore sessions;
    private final MemoryService memoryService;

    public ContextManager(UserSessionStore sessions, MemoryService memoryService) {
        this.sessions = sessions;
        this.memoryService = memoryService;
    }

    public ContextData buildContext(String userId) {
        ConversationSession session = sessions.getCurrentSession(userId);
        return new ContextData(session.sessionId(), sessions.getPersonaPrompt(userId), memoryService.prompt(userId));
    }

    public record ContextData(String sessionId, String persona, String memories) { }
}
