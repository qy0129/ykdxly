package com.example.ilink.adapter.outbound.wechat;

import com.example.ilink.application.messaging.ReplyChannel;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.util.Objects;

/** WeChat SDK implementation of the channel-neutral outbound port. */
public final class WechatReplyChannel implements ReplyChannel {

    private final ILinkClient client;

    public WechatReplyChannel(ILinkClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override public void startTyping(String recipientId) throws Exception {
        client.startTyping(recipientId);
    }

    @Override public void sendText(String recipientId, String text) throws Exception {
        client.sendText(recipientId, text);
    }

    @Override public void sendImage(String recipientId, byte[] content,
                                    String fileName, String caption) throws Exception {
        client.sendImage(recipientId, content, fileName, caption);
    }

    @Override public void sendFile(String recipientId, byte[] content,
                                   String fileName, String caption) throws Exception {
        client.sendFile(recipientId, content, fileName, caption);
    }
}
