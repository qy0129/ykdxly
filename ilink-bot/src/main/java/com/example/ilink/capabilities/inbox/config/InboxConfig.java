package com.example.ilink.capabilities.inbox.config;

/** Inbox 本地处理配置。 */
public record InboxConfig(int maxContentLength, int summaryThreshold, int maxSummaryLength) {
    public static InboxConfig defaultConfig() {
        return new InboxConfig(20000, 80, 200);
    }
}
