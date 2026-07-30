package com.example.ilink.adapter.outbound.wechat;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ChannelType;
import com.example.ilink.application.messaging.RequestLogContext;
import com.example.ilink.application.integration.WechatWebBridge;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.util.Objects;

/** WeChat SDK implementation of the channel-neutral outbound port. */
public final class WechatReplyChannel implements ReplyChannel {

    private final ILinkClient client;
    private final WechatWebBridge bridge;

    public WechatReplyChannel(ILinkClient client) {
        this(client, null);
    }

    public WechatReplyChannel(ILinkClient client, WechatWebBridge bridge) {
        this.client = Objects.requireNonNull(client, "client");
        this.bridge = bridge;
    }

    @Override public void startTyping(String recipientId) throws Exception {
        client.startTyping(recipientId);
    }

    @Override public void sendText(String recipientId, String text) throws Exception {
        System.out.println(RequestLogContext.prefixFor(ChannelType.WECHAT, "回复发送", recipientId)
                + " kind=text chars=" + (text == null ? 0 : text.length())
                + " preview=" + RequestLogContext.preview(text));
        client.sendText(recipientId, text);
        if (bridge != null) bridge.recordOutgoing(recipientId, text, "bot_reply");
    }

    @Override public void sendImage(String recipientId, byte[] content,
                                    String fileName, String caption) throws Exception {
        System.out.println(RequestLogContext.prefixFor(ChannelType.WECHAT, "回复发送", recipientId)
                + " kind=image file=" + RequestLogContext.preview(fileName)
                + " bytes=" + (content == null ? 0 : content.length)
                + " caption=" + RequestLogContext.preview(caption));
        client.sendImage(recipientId, content, fileName, caption);
        if (bridge != null) bridge.recordOutgoing(recipientId, fileName, "image");
    }

    @Override public void sendFile(String recipientId, byte[] content,
                                   String fileName, String caption) throws Exception {
        System.out.println(RequestLogContext.prefixFor(ChannelType.WECHAT, "回复发送", recipientId)
                + " kind=file file=" + RequestLogContext.preview(fileName)
                + " bytes=" + (content == null ? 0 : content.length)
                + " caption=" + RequestLogContext.preview(caption));
        client.sendFile(recipientId, content, fileName, caption);
        if (bridge != null) bridge.recordOutgoing(recipientId, fileName, "file");
    }
}
