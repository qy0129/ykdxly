package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 主动通知的可靠 Outbox。 */
public final class NotificationOutbox {
    static final int MAX_ATTEMPTS = 5;
    private final ExecutiveTaskStore store;

    public NotificationOutbox(ExecutiveTaskStore store) {
        this.store = store;
    }

    public void enqueue(String taskId, String userId, String type, String content) {
        store.saveOutbox(new OutboxMessage(UUID.randomUUID().toString(), taskId, userId,
                type, content, "PENDING", 0, LocalDateTime.now(), null, LocalDateTime.now()));
    }

    public List<OutboxMessage> pending(String userId, int limit) {
        return store.pendingOutbox(userId, LocalDateTime.now(), limit);
    }

    public void markSent(OutboxMessage message) {
        store.saveOutbox(new OutboxMessage(message.id(), message.taskId(), message.userId(),
                message.type(), message.content(), "SENT", message.attempts(), message.availableAt(),
                LocalDateTime.now(), message.createdAt()));
    }

    public void markFailed(OutboxMessage message) {
        int attempts = message.attempts() + 1;
        String status = attempts >= MAX_ATTEMPTS ? "DEAD_LETTER" : "PENDING";
        store.saveOutbox(new OutboxMessage(message.id(), message.taskId(), message.userId(),
                message.type(), message.content(), status, attempts,
                LocalDateTime.now().plusMinutes(backoffMinutes(attempts)), null, message.createdAt()));
    }

    private static int backoffMinutes(int attempts) {
        return Math.min(30, 1 << Math.min(4, Math.max(0, attempts - 1)));
    }
}
