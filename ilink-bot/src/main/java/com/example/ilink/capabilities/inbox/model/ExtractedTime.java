package com.example.ilink.capabilities.inbox.model;

import java.time.LocalDateTime;

/** 从消息中识别出的时间表达。 */
public record ExtractedTime(String expression, LocalDateTime resolvedAt, boolean deadline) {
    public boolean isDeadline() { return deadline; }
}
