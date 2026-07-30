package com.example.ilink.application.messaging;

import java.util.Objects;

/** Per-request context shared by workflows without exposing a transport SDK. */
public record AgentContext(AgentIdentity identity, ChannelType channel,
                           ChannelCapabilities capabilities, ReplyChannel replyChannel,
                           String workspaceId) {

    public AgentContext {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(replyChannel, "replyChannel");
    }

    public static AgentContext wechat(String userId, ReplyChannel replyChannel) {
        return new AgentContext(AgentIdentity.direct(userId), ChannelType.WECHAT,
                ChannelCapabilities.wechat(), replyChannel, null);
    }

    public static AgentContext web(String userId, String conversationId,
                                   ReplyChannel replyChannel, String workspaceId) {
        return new AgentContext(new AgentIdentity(userId, conversationId), ChannelType.WEB,
                ChannelCapabilities.web(), replyChannel, workspaceId);
    }

    public String principalId() { return identity.principalId(); }

    public String conversationId() { return identity.conversationId(); }

    /** Isolates mutable workflow state between Web conversations without changing user ownership. */
    public String conversationScopeId() {
        return channel == ChannelType.WEB
                ? principalId() + "|web-conversation|" + conversationId()
                : principalId();
    }
}
