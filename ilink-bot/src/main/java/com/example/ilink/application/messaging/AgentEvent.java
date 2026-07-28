package com.example.ilink.application.messaging;

import java.util.Map;

/** Channel-neutral event reserved for streaming agent progress and results. */
public record AgentEvent(Type type, String content, Map<String, Object> metadata) {

    public AgentEvent {
        type = type == null ? Type.STATUS : type;
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public enum Type {
        STATUS,
        TEXT_DELTA,
        TOOL_ACTIVITY,
        COMPLETED,
        ERROR
    }
}
