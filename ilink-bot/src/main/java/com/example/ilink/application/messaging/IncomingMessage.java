package com.example.ilink.application.messaging;

import java.util.List;
import java.util.Objects;
import java.time.Instant;
import java.util.UUID;

/** Channel-neutral inbound message consumed by the application layer. */
public record IncomingMessage(AgentIdentity identity, List<MessagePart> parts, String messageId,
                              Instant receivedAt, String sourceType) {

    public IncomingMessage(AgentIdentity identity, List<MessagePart> parts) {
        this(identity, parts, UUID.randomUUID().toString(), Instant.now(), "PRIVATE");
    }

    public IncomingMessage {
        Objects.requireNonNull(identity, "identity");
        parts = parts == null ? List.of() : List.copyOf(parts);
        messageId = messageId == null || messageId.isBlank() ? UUID.randomUUID().toString() : messageId;
        receivedAt = receivedAt == null ? Instant.now() : receivedAt;
        sourceType = sourceType == null || sourceType.isBlank() ? "PRIVATE" : sourceType;
    }

    public String principalId() {
        return identity.principalId();
    }
}
