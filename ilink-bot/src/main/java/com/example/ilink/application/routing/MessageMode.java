package com.example.ilink.application.routing;

/** 描述用户消息是主动请求、被动通知还是普通对话。 */
public enum MessageMode {
    COMMAND,
    PASSIVE_MESSAGE,
    CHAT,
    CONTINUATION,
    AMBIGUOUS;

    public static MessageMode fromModel(String value) {
        if (value == null) return COMMAND;
        return switch (value.trim().toLowerCase()) {
            case "passive_message", "passive", "notification" -> PASSIVE_MESSAGE;
            case "chat" -> CHAT;
            case "continuation", "continue" -> CONTINUATION;
            case "ambiguous", "unclear" -> AMBIGUOUS;
            default -> COMMAND;
        };
    }

    public String modelValue() {
        return name().toLowerCase();
    }
}
