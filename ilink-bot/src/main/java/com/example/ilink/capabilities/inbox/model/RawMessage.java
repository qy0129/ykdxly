package com.example.ilink.capabilities.inbox.model;

import java.time.Instant;

/** 微信入口交给 Inbox 的原始消息。 */
public record RawMessage(String msgId, String senderId, String senderName,
                         String content, Instant receivedAt, ProcessedMessage.SourceType sourceType,
                         String conversationId) {
    public RawMessage(String msgId, String senderId, String senderName, String content, Instant receivedAt) {
        this(msgId, senderId, senderName, content, receivedAt,
                ProcessedMessage.SourceType.PRIVATE, "");
    }

    public static RawMessage fromWechat(String msgId, String senderId, String senderName, String content) {
        return new RawMessage(msgId, senderId, senderName, content, Instant.now(),
                ProcessedMessage.SourceType.PRIVATE, "");
    }
}
