package com.example.ilink.application.messaging;

import java.util.Objects;

/** Stable user and conversation identifiers supplied by an inbound adapter. */
public record AgentIdentity(String principalId, String conversationId) {

    public AgentIdentity {
        principalId = requireText(principalId, "principalId");
        conversationId = conversationId == null || conversationId.isBlank() ? principalId : conversationId;
    }

    public static AgentIdentity direct(String principalId) {
        return new AgentIdentity(principalId, principalId);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
