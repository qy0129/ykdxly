package com.example.ilink.adapter.inbound.wechat;

import com.example.ilink.application.messaging.AgentIdentity;
import com.example.ilink.application.messaging.IncomingMessage;
import com.example.ilink.application.messaging.MessagePart;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Converts WeChat SDK messages into the channel-neutral application protocol. */
public final class WechatMessageAdapter {

    public IncomingMessage adapt(ILinkClient client, WeixinMessage message) throws Exception {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(message, "message");
        return adapt(message, client);
    }

    /** Text-only overload used by adapter tests and callers that do not need SDK downloads. */
    public IncomingMessage adapt(WeixinMessage message) {
        Objects.requireNonNull(message, "message");
        try {
            return adapt(message, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("Media messages require an ILinkClient", error);
        }
    }

    private IncomingMessage adapt(WeixinMessage message, ILinkClient client) throws Exception {
        List<MessagePart> parts = new ArrayList<>();
        for (MessageItem item : safeItems(message)) {
            if (item.getText_item() != null) {
                parts.add(new MessagePart.Text(item.getText_item().getText()));
            } else if (item.getImage_item() != null) {
                requireClient(client);
                parts.add(new MessagePart.Image(
                        client.downloadImageFromMessageItem(item), "wechat-image"));
            } else if (item.getVoice_item() != null) {
                requireClient(client);
                parts.add(new MessagePart.Voice(
                        client.downloadVoiceFromMessageItem(item),
                        item.getVoice_item().getText(), "wechat-voice"));
            } else if (item.getFile_item() != null) {
                requireClient(client);
                parts.add(new MessagePart.File(
                        client.downloadFileFromMessageItem(item),
                        item.getFile_item().getFile_name()));
            } else if (item.getVideo_item() != null) {
                requireClient(client);
                parts.add(new MessagePart.Video(
                        client.downloadVideoFromMessageItem(item), "wechat-video.mp4"));
            }
        }
        String userId = message.getFrom_user_id();
        return new IncomingMessage(AgentIdentity.direct(userId), parts);
    }

    private static List<MessageItem> safeItems(WeixinMessage message) {
        return message.getItem_list() == null ? List.of() : message.getItem_list();
    }

    private static void requireClient(ILinkClient client) {
        if (client == null) throw new IllegalArgumentException("Media messages require an ILinkClient");
    }
}
