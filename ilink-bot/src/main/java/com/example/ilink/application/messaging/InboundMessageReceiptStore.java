package com.example.ilink.application.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.example.ilink.platform.persistence.MySqlStore;

/**
 * 入站消息幂等闸门。数据库不可用时仍能阻止 SDK 在同一进程内重复投递，
 * 并通过租约允许崩溃后的消息重新处理。
 */
public final class InboundMessageReceiptStore {
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(5);

    private final Map<String, Receipt> receipts = new ConcurrentHashMap<>();
    private final MySqlStore database;

    public InboundMessageReceiptStore() { this(true); }

    InboundMessageReceiptStore(boolean persistent) {
        this.database = persistent ? MySqlStore.getInstance() : null;
    }

    public Claim claim(String channel, String accountId, String messageId, String userId, Instant now) {
        String key = key(channel, accountId, messageId);
        if (database != null && database.isAvailable()) {
            String status = database.claimInboundMessage(channel, accountId, messageId,
                    userId, now.plus(LEASE));
            if ("ACCEPTED".equals(status)) return Claim.accepted(key, channel, accountId, messageId);
            if ("COMPLETED".equals(status)) return Claim.completed(key, channel, accountId, messageId);
            if ("PROCESSING".equals(status)) return Claim.processing(key, channel, accountId, messageId);
        }
        Receipt next = new Receipt(now.plus(LEASE), false);
        Receipt existing = receipts.putIfAbsent(key, next);
        if (existing == null) return Claim.accepted(key, channel, accountId, messageId);
        if (existing.completed()) return Claim.completed(key, channel, accountId, messageId);
        if (existing.leaseUntil().isBefore(now)) {
            if (receipts.replace(key, existing, next)) return Claim.accepted(key, channel, accountId, messageId);
        }
        return Claim.processing(key, channel, accountId, messageId);
    }

    public void complete(Claim claim) {
        if (claim == null || !claim.accepted()) return;
        if (database != null && database.isAvailable()) {
            database.completeInboundMessage(claim.channel(), claim.accountId(), claim.messageId());
            return;
        }
        receipts.computeIfPresent(claim.key(), (ignored, value) -> new Receipt(value.leaseUntil(), true));
        cleanup(Instant.now());
    }

    public void retryable(Claim claim) {
        if (claim == null || !claim.accepted()) return;
        if (database != null && database.isAvailable()) {
            database.releaseInboundMessage(claim.channel(), claim.accountId(), claim.messageId());
            return;
        }
        receipts.remove(claim.key(), receipts.get(claim.key()));
    }

    private void cleanup(Instant now) {
        Instant threshold = now.minus(RETENTION);
        receipts.entrySet().removeIf(entry -> entry.getValue().completed()
                && entry.getValue().leaseUntil().isBefore(threshold));
    }

    private String key(String channel, String accountId, String messageId) {
        return text(channel) + ":" + text(accountId) + ":" + text(messageId);
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    private record Receipt(Instant leaseUntil, boolean completed) { }

    public record Claim(String key, String channel, String accountId, String messageId, Status status) {
        static Claim accepted(String key, String channel, String accountId, String messageId) {
            return new Claim(key, channel, accountId, messageId, Status.ACCEPTED);
        }
        static Claim completed(String key, String channel, String accountId, String messageId) {
            return new Claim(key, channel, accountId, messageId, Status.COMPLETED);
        }
        static Claim processing(String key, String channel, String accountId, String messageId) {
            return new Claim(key, channel, accountId, messageId, Status.PROCESSING);
        }
        public boolean accepted() { return status == Status.ACCEPTED; }
        public boolean completed() { return status == Status.COMPLETED; }
    }

    public enum Status { ACCEPTED, PROCESSING, COMPLETED }
}
