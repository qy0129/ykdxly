package com.example.ilink.capabilities.inbox.service;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.model.RawMessage;

/** 清理消息空白并限制输入长度。 */
public final class MessagePreprocessor {
    private final InboxConfig config;

    public MessagePreprocessor(InboxConfig config) {
        this.config = config;
    }

    public ProcessedMessage process(RawMessage message) {
        String content = message.content() == null ? "" : message.content().replaceAll("\\s+", " ").trim();
        if (content.length() > config.maxContentLength()) content = content.substring(0, config.maxContentLength());
        return new ProcessedMessage(message.msgId(), content, message.sourceType(), message.conversationId(),
                message.senderId(), message.senderName(), message.receivedAt());
    }
}
