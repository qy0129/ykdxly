package com.example.ilink.capabilities.inbox.model;

import java.util.List;

/** 消息摘要、分类和优先级。 */
public record MessageSummary(String summary, List<String> keywords, MessageType messageType,
                             Priority priority, boolean direct) {
    public MessageSummary {
        summary = summary == null ? "" : summary;
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        messageType = messageType == null ? MessageType.OTHER : messageType;
        priority = priority == null ? Priority.LOW : priority;
    }

    public static MessageSummary direct(String content) {
        return new MessageSummary(content, List.of(), MessageType.OTHER, Priority.LOW, true);
    }

    public static MessageSummary summarized(String summary, List<String> keywords,
                                            MessageType type, Priority priority) {
        return new MessageSummary(summary, keywords, type, priority, false);
    }

    public boolean isDirect() { return direct; }
    public int summaryLength() { return summary.length(); }
    public boolean hasKeywords() { return !keywords.isEmpty(); }

    public enum MessageType { TASK, NOTIFICATION, INQUIRY, CHAT, OTHER }
    public enum Priority { URGENT, HIGH, MEDIUM, LOW }
}
