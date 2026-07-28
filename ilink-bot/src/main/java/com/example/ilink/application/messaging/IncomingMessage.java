package com.example.ilink.application.messaging;

import java.util.List;
import java.util.Objects;

/** Channel-neutral inbound message consumed by the application layer. */
public record IncomingMessage(AgentIdentity identity, List<MessagePart> parts) {

    public IncomingMessage {
        Objects.requireNonNull(identity, "identity");
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    public String principalId() {
        return identity.principalId();
    }
}
