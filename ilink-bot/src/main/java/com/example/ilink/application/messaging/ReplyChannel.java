package com.example.ilink.application.messaging;

/** Outbound transport port implemented by WeChat, Web, or another adapter. */
public interface ReplyChannel {

    void startTyping(String recipientId) throws Exception;

    void sendText(String recipientId, String text) throws Exception;

    void sendImage(String recipientId, byte[] content, String fileName, String caption) throws Exception;

    void sendFile(String recipientId, byte[] content, String fileName, String caption) throws Exception;

    /** Whether this channel stores outbound media as structured conversation history. */
    default boolean persistsOutboundMedia() {
        return false;
    }

    default void publish(String recipientId, AgentEvent event) throws Exception {
        if (event.type() == AgentEvent.Type.TEXT_DELTA || event.type() == AgentEvent.Type.COMPLETED) {
            sendText(recipientId, event.content());
        }
    }
}
