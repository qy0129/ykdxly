package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebAgentContextTest {

    @Test
    void createsWebIdentityAndCapabilities() {
        ReplyChannel channel = new ReplyChannel() {
            @Override public void startTyping(String recipientId) { }
            @Override public void sendText(String recipientId, String text) { }
            @Override public void sendImage(String recipientId, byte[] content, String fileName, String caption) { }
            @Override public void sendFile(String recipientId, byte[] content, String fileName, String caption) { }
        };

        AgentContext context = AgentContext.web("web-user", "conversation-1", channel, "workspace-1");

        assertEquals(ChannelType.WEB, context.channel());
        assertEquals("web-user", context.principalId());
        assertEquals("conversation-1", context.conversationId());
        assertEquals("web-user|web-conversation|conversation-1", context.conversationScopeId());
        assertEquals("workspace-1", context.workspaceId());
        assertTrue(context.capabilities().images());
        assertTrue(context.capabilities().files());
        assertSame(channel, context.replyChannel());
    }

    @Test
    void isolatesMutableWorkflowStateByWebConversation() {
        ReplyChannel channel = new ReplyChannel() {
            @Override public void startTyping(String recipientId) { }
            @Override public void sendText(String recipientId, String text) { }
            @Override public void sendImage(String recipientId, byte[] content, String fileName, String caption) { }
            @Override public void sendFile(String recipientId, byte[] content, String fileName, String caption) { }
        };

        AgentContext first = AgentContext.web("web-user", "conversation-1", channel, "workspace-1");
        AgentContext second = AgentContext.web("web-user", "conversation-2", channel, "workspace-1");

        assertEquals("web-user", first.principalId());
        assertTrue(!first.conversationScopeId().equals(second.conversationScopeId()));
        assertEquals("wechat-user", AgentContext.wechat("wechat-user", channel).conversationScopeId());
    }
}
