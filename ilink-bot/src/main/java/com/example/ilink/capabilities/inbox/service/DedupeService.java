package com.example.ilink.capabilities.inbox.service;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.DedupeResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 按消息 ID 和发送者内容哈希去重。 */
public final class DedupeService {
    private static final Duration MESSAGE_ID_TTL = Duration.ofHours(24);
    private static final Duration CONTENT_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHE_SIZE = 10_000;
    private final Map<String, Instant> messageIds = new ConcurrentHashMap<>();
    private final Map<String, Instant> contentHashes = new ConcurrentHashMap<>();
    private final DedupeStore store;

    public DedupeService(InboxConfig config, DedupeStore store) {
        this.store = store;
    }

    public DedupeResult check(String messageId, String content, String senderId) {
        Instant now = Instant.now();
        cleanup(now);
        if (active(messageIds, messageId, now, MESSAGE_ID_TTL)
                || store != null && store.containsMessageId(messageId)) {
            return DedupeResult.duplicate(DedupeResult.DUPLICATE_MSG_ID);
        }
        String hash = hash(senderId + "\n" + (content == null ? "" : content));
        if (active(contentHashes, hash, now, CONTENT_TTL)
                || store != null && store.containsContentHash(hash)) {
            return DedupeResult.duplicate(DedupeResult.DUPLICATE_CONTENT_HASH);
        }
        record(messageId, content, senderId);
        return DedupeResult.unique();
    }

    public void record(String messageId, String content, String senderId) {
        String hash = hash(senderId + "\n" + (content == null ? "" : content));
        Instant now = Instant.now();
        if (messageId != null && !messageId.isBlank()) messageIds.put(messageId, now);
        contentHashes.put(hash, now);
        if (store != null) store.record(messageId, hash);
    }

    public DedupeStats getStats() {
        return new DedupeStats(messageIds.size(), contentHashes.size());
    }

    private String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (Exception error) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static boolean active(Map<String, Instant> values, String key, Instant now, Duration ttl) {
        if (key == null || key.isBlank()) return false;
        Instant recordedAt = values.get(key);
        return recordedAt != null && recordedAt.plus(ttl).isAfter(now);
    }

    private void cleanup(Instant now) {
        if (messageIds.size() > MAX_CACHE_SIZE) {
            messageIds.entrySet().removeIf(entry -> entry.getValue().plus(MESSAGE_ID_TTL).isBefore(now));
        }
        if (contentHashes.size() > MAX_CACHE_SIZE) {
            contentHashes.entrySet().removeIf(entry -> entry.getValue().plus(CONTENT_TTL).isBefore(now));
        }
    }

    public interface DedupeStore {
        boolean containsMessageId(String messageId);
        boolean containsContentHash(String hash);
        void record(String messageId, String contentHash);
    }

    public record DedupeStats(int messageIdCacheSize, int contentHashCacheSize) { }
}
