package com.example.ilink.adapter.inbound.wechat;

import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.example.ilink.application.messaging.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class WechatMessageAdapterTest {

    @Test
    void convertsSdkMessageToApplicationMessage() {
        WeixinMessage source = new WeixinMessage();
        source.setFrom_user_id("user-1");
        source.setItem_list(List.of(MessageItem.text("hello")));

        var result = new WechatMessageAdapter().adapt(source);

        assertEquals("user-1", result.principalId());
        MessagePart.Text text = assertInstanceOf(MessagePart.Text.class, result.parts().get(0));
        assertEquals("hello", text.text());
    }
}
