package com.example.ilink.capabilities.inbox.model;

/** 消息去重结果。 */
public record DedupeResult(boolean duplicate, String reason) {
    public static final String DUPLICATE_MSG_ID = "DUPLICATE_MSG_ID";
    public static final String DUPLICATE_CONTENT_HASH = "DUPLICATE_CONTENT_HASH";

    public static DedupeResult unique() {
        return new DedupeResult(false, null);
    }

    public static DedupeResult duplicate(String reason) {
        return new DedupeResult(true, reason);
    }

    public boolean isDuplicate() {
        return duplicate;
    }
}
