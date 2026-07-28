package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.platform.persistence.MySqlStore;

import java.util.List;

/**
 * AI 上下文构建器。
 *
 * <p>加载人设、当前 Session 聊天记录、历史摘要、长期记忆，
 * 生成结构化的 AI Context 字符串。</p>
 */
public final class ContextManager {

    private final MySqlStore database;
    private final UserSessionStore sessions;
    private final MemoryService memoryService;

    public ContextManager(UserSessionStore sessions, MemoryService memoryService) {
        this.database = MySqlStore.getInstance();
        this.sessions = sessions;
        this.memoryService = memoryService;
    }

    /** 构建 AI 上下文，包含人设 + 摘要 + 记忆 + 最近消息。 */
    public ContextData buildContext(String userId) {
        ConversationSession session = sessions.getCurrentSession(userId);
        String sessionId = session == null ? null : session.sessionId();

        String persona = sessions.getPersonaPrompt(userId);
        String memories = memoryService.prompt(userId);
        String summary = sessionId == null ? null : database.loadSessionSummary(sessionId);
        List<MySqlStore.ChatEntry> recentMessages = sessionId == null
                ? List.of() : database.loadSessionMessages(sessionId, 20);

        return new ContextData(persona, memories, summary, recentMessages);
    }

    /** 上下文数据。 */
    public record ContextData(String persona, String memories, String summary,
                              List<MySqlStore.ChatEntry> recentMessages) {
    }
}
