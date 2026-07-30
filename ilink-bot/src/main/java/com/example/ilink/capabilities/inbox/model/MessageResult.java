package com.example.ilink.capabilities.inbox.model;

/** Inbox 对外返回结果。 */
public record MessageResult(String messageId, boolean success, boolean duplicate,
                            String statusReason, MessageSummary summary,
                            ExtractionResult extraction) {
    public static MessageResult failed(String messageId, String reason) {
        return new MessageResult(messageId, false, false, reason, null,
                ExtractionResult.empty(MessageSummary.MessageType.OTHER));
    }

    public static MessageResult duplicate(String messageId, String reason) {
        return new MessageResult(messageId, true, true, reason, null,
                ExtractionResult.empty(MessageSummary.MessageType.OTHER));
    }

    public boolean isSuccess() { return success; }
    public boolean isDuplicate() { return duplicate; }
    public boolean hasTasks() { return extraction != null && extraction.hasTasks(); }
    public boolean hasTimes() { return extraction != null && extraction.hasTimes(); }
    public int taskCount() { return extraction == null ? 0 : extraction.taskCount(); }
    public int timeCount() { return extraction == null ? 0 : extraction.timeCount(); }
}
