package com.example.ilink.application.messaging;

/** 将文本请求交给业务能力处理器。 */
public final class CapabilityDispatcher {

    private final UserRequestHandler requestHandler;

    public CapabilityDispatcher(UserRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public void dispatch(AgentContext context, String text) throws Exception {
        requestHandler.handle(context, text);
    }
}
