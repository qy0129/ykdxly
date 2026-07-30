package com.example.ilink.capabilities.inbox.service;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;

import java.util.ArrayList;
import java.util.List;

/** 轻量摘要、分类和优先级估计。 */
public final class SummaryService {
    private final InboxConfig config;
    private long total;

    public SummaryService(InboxConfig config) {
        this.config = config;
    }

    public MessageSummary summarize(ProcessedMessage message) {
        total++;
        String content = message.cleanedContent();
        String summary = content.length() <= config.maxSummaryLength()
                ? content : content.substring(0, config.maxSummaryLength());
        MessageSummary.MessageType type = classify(content);
        MessageSummary.Priority priority = priority(content);
        return MessageSummary.summarized(summary, keywords(content), type, priority);
    }

    private MessageSummary.MessageType classify(String value) {
        if (value.matches("(?s).*(需要|完成|提交|任务|安排|准备).*")) return MessageSummary.MessageType.TASK;
        if (value.matches("(?s).*(通知|公告|会议|参加|提醒).*")) return MessageSummary.MessageType.NOTIFICATION;
        if (value.matches("(?s).*(咨询|吗[？?]?|有没有|是否).*")) return MessageSummary.MessageType.INQUIRY;
        return value.length() < config.summaryThreshold()
                ? MessageSummary.MessageType.OTHER : MessageSummary.MessageType.CHAT;
    }

    private MessageSummary.Priority priority(String value) {
        if (value.matches("(?s).*(紧急|立即|马上).*")) return MessageSummary.Priority.URGENT;
        if (value.matches("(?s).*(重要|优先|今天.*完成).*")) return MessageSummary.Priority.HIGH;
        return MessageSummary.Priority.LOW;
    }

    private List<String> keywords(String value) {
        List<String> result = new ArrayList<>();
        for (String keyword : List.of("项目", "报告", "会议", "提交", "明天", "今天", "任务")) {
            if (value.contains(keyword)) result.add(keyword);
        }
        return result;
    }

    public SummaryStats getStats() { return new SummaryStats(total); }
    public record SummaryStats(long total) { }
}
