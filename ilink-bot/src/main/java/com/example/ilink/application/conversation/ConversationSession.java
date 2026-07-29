package com.example.ilink.application.conversation;

import java.time.LocalDateTime;
import java.util.Objects;

/** 当前活跃会话的轻量状态。 */
public record ConversationSession(String userId, String sessionId, LocalDateTime createdAt,
                                  LocalDateTime lastActiveAt) {
    public ConversationSession {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(lastActiveAt, "lastActiveAt");
    }
}
