package com.example.ilink.adapter.outbound.wechat;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ChannelType;
import com.example.ilink.application.messaging.ConsoleLog;
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
        try {
            client.sendText(recipientId, text);
        } catch (Exception error) {
            invalidateContextIfNeeded(recipientId, error);
            throw error;
        }
        if (bridge != null) bridge.recordOutgoing(recipientId, text, "bot_reply");
        ConsoleLog.botMessage(ChannelType.WECHAT, recipientId, text);
    }

    @Override public void sendImage(String recipientId, byte[] content,
                                    String fileName, String caption) throws Exception {
        ConsoleLog.info("回复发送", "向用户发送图片，用户标识=" + recipientId + "，文件名="
                + ConsoleLog.summary(fileName) + "，文件大小=" + (content == null ? 0 : content.length)
                + "字节，说明=" + ConsoleLog.summary(caption));
        try {
            client.sendImage(recipientId, content, fileName, caption);
        } catch (Exception error) {
            invalidateContextIfNeeded(recipientId, error);
            throw error;
        }
        if (bridge != null) bridge.recordOutgoingImage(recipientId, content, fileName, caption);
    }

    @Override public void sendFile(String recipientId, byte[] content,
                                   String fileName, String caption) throws Exception {
        ConsoleLog.info("回复发送", "向用户发送文件，用户标识=" + recipientId + "，文件名="
                + ConsoleLog.summary(fileName) + "，文件大小=" + (content == null ? 0 : content.length)
                + "字节，说明=" + ConsoleLog.summary(caption));
        try {
            client.sendFile(recipientId, content, fileName, caption);
        } catch (Exception error) {
            invalidateContextIfNeeded(recipientId, error);
            throw error;
        }
        if (bridge != null) bridge.recordOutgoingFile(recipientId, content, fileName, caption);
    }

    private void invalidateContextIfNeeded(String recipientId, Exception error) throws WechatContextInvalidException {
        if (!WechatContextInvalidException.matches(error)) return;
        try {
            client.clearContext(recipientId);
        } catch (Exception clearError) {
            ConsoleLog.warn("微信上下文", "清理失败，用户标识=" + recipientId + "，"
                    + ConsoleLog.errorSummary(clearError));
        }
        ConsoleLog.warn("微信上下文", "服务端拒绝会话凭据，已清理本地上下文，用户标识=" + recipientId);
        throw new WechatContextInvalidException(recipientId, error);
    }
}
