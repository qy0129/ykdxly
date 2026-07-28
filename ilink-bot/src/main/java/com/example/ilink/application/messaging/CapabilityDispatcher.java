package com.example.ilink.application.messaging;

import com.github.wechat.ilink.sdk.ILinkClient;

/** 将文本请求交给业务能力处理器。 */
public final class CapabilityDispatcher {

    private final UserRequestHandler requestHandler;

    public CapabilityDispatcher(UserRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public void dispatch(ILinkClient client, String userId, String text) throws Exception {
        requestHandler.handle(client, userId, text);
    }
}
