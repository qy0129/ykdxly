package com.example.ilink.adapter.inbound.wechat;

import com.example.ilink.application.messaging.IncomingMessage;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.Objects;

/** 将微信 SDK 消息转换为应用层消息。 */
public final class WechatMessageAdapter {

    public IncomingMessage adapt(WeixinMessage message) {
        Objects.requireNonNull(message, "message");
        return new IncomingMessage(message.getFrom_user_id(), message.getItem_list());
    }
}
