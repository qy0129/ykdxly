package com.example.ilink.application.messaging;

import com.example.ilink.application.routing.IntentPlan;

/** 将文本请求交给业务能力处理器。 */
public final class CapabilityDispatcher {

    private final UserRequestHandler requestHandler;

    public CapabilityDispatcher(UserRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public void dispatch(AgentContext context, String text) throws Exception {
        dispatch(context, text, null);
    }

    public void dispatch(AgentContext context, String text, IntentPlan plan) throws Exception {
        requestHandler.handle(context, text, plan);
    }

    public IntentPlan analyze(AgentContext context, String text) {
        return requestHandler.analyze(context, text);
    }

    public boolean hasPendingInteraction(String userId) {
        return requestHandler.hasPendingInteraction(userId);
    }
}
