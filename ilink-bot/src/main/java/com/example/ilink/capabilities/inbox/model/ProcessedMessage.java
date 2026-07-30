package com.example.ilink.capabilities.inbox.model;

import java.time.Instant;

/** 标准化后的消息。 */
public record ProcessedMessage(String messageId, String cleanedContent, SourceType sourceType,
                               String conversationId, String senderId, String senderName,
                               Instant receivedAt) {
    public enum SourceType { PRIVATE, GROUP, FILE, OTHER }
}
