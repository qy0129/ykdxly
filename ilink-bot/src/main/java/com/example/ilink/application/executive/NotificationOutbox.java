package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 主动通知的可靠 Outbox。 */
public final class NotificationOutbox {
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
        store.saveOutbox(new OutboxMessage(message.id(), message.taskId(), message.userId(),
                message.type(), message.content(), "PENDING", message.attempts() + 1,
                LocalDateTime.now().plusMinutes(1), null, message.createdAt()));
    }
}
