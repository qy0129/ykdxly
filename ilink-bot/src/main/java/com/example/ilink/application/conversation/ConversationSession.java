package com.example.ilink.application.conversation;

import com.example.ilink.application.messaging.IncomingMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 表示一次用户聊天会话。
 *
 * <p>只负责保存会话状态，不包含数据库操作、Redis操作、微信逻辑或大模型调用等业务逻辑。</p>
 */
public record ConversationSession(
        String userId,
        String sessionId,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt,
        List<IncomingMessage> messages,
        Map<String, Object> taskState) {

    public ConversationSession {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(lastActiveAt, "lastActiveAt must not be null");
        messages = messages == null ? List.of() : List.copyOf(messages);
        taskState = taskState == null ? Map.of() : Map.copyOf(taskState);
    }
}
