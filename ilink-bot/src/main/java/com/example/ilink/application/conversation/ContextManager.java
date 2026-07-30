package com.example.ilink.application.conversation;

import java.util.List;

/**
 * 统一触发用户级上下文加载。
 * 通过三个 Provider 分别构建会话上下文、记忆上下文和知识上下文，
 * ChatService 和路由层各自按需读取，避免直接依赖底层服务。
 */
public final class ContextManager {

    private final ConversationContextProvider conversationProvider;
    private final MemoryContextProvider memoryProvider;
    private final KnowledgeContextProvider knowledgeProvider;

    public ContextManager(ConversationContextProvider conversationProvider,
                          MemoryContextProvider memoryProvider,
                          KnowledgeContextProvider knowledgeProvider) {
        this.conversationProvider = conversationProvider;
        this.memoryProvider = memoryProvider;
        this.knowledgeProvider = knowledgeProvider;
    }

    public ConversationContext buildConversation(String userId) {
        return conversationProvider.build(userId);
    }

    public ConversationContext buildConversation(String userId, String sessionId) {
        return conversationProvider.build(userId, sessionId);
    }

    public MemoryContext buildMemory(String userId) {
        return memoryProvider.build(userId);
    }

    public KnowledgeContext buildKnowledge(String userId, String query) {
        return knowledgeProvider.build(userId, query);
    }

    /** @deprecated 迁移期兼容，新代码请使用各 build* 方法。 */
    @Deprecated
    public ContextData buildContext(String userId) {
        ConversationContext conv = buildConversation(userId);
        MemoryContext mem = buildMemory(userId);
        return new ContextData(conv.sessionId(), conv.persona(), mem.memories(),
                conv.summary(), conv.recentMessages());
    }

    @Deprecated
    public record ContextData(String sessionId, String persona, String memories, String summary,
                              List<com.example.ilink.platform.persistence.MySqlStore.ChatEntry> recentMessages) { }
}
