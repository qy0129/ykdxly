package com.example.ilink.application.messaging;

import com.github.wechat.ilink.sdk.core.model.MessageItem;

import java.util.List;

/** 应用层使用的入站消息。 */
public record IncomingMessage(String userId, List<MessageItem> items) {

    public IncomingMessage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
