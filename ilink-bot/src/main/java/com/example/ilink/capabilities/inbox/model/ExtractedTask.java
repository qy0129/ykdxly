package com.example.ilink.capabilities.inbox.model;

import java.time.LocalDateTime;

/** 从消息中识别出的待办。 */
public record ExtractedTask(String title, LocalDateTime deadline) { }
