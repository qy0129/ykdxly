package com.example.ilink.capabilities.calendar;

import java.time.LocalDateTime;

/** 一次具体的提醒投递，用于失败重试、离线补发和去重。 */
public record ReminderDelivery(
        String id,
        String eventId,
        String userId,
        LocalDateTime scheduledAt,
        String status,
        int retryCount,
        LocalDateTime nextRetryAt,
        LocalDateTime sentAt,
        String errorMessage,
        String dedupKey,
        LocalDateTime lockedUntil) {

    public ReminderDelivery claiming(LocalDateTime now) {
        return new ReminderDelivery(id, eventId, userId, scheduledAt, "sending", retryCount,
                nextRetryAt, sentAt, errorMessage, dedupKey, now.plusMinutes(2));
    }

    public ReminderDelivery sent(LocalDateTime now) {
        return new ReminderDelivery(id, eventId, userId, scheduledAt, "sent", retryCount,
                null, now, "", dedupKey, null);
    }

    public ReminderDelivery failed(LocalDateTime now, String error) {
        int retries = retryCount + 1;
        int delayMinutes = switch (retries) {
            case 1 -> 1;
            case 2 -> 5;
            case 3 -> 15;
            default -> 30;
        };
        return new ReminderDelivery(id, eventId, userId, scheduledAt, "failed", retries,
                now.plusMinutes(delayMinutes), sentAt, error == null ? "" : error, dedupKey, null);
    }

    public ReminderDelivery cancelled() {
        return new ReminderDelivery(id, eventId, userId, scheduledAt, "cancelled", retryCount,
                null, sentAt, errorMessage, dedupKey, null);
    }
}
