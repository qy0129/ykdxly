package com.example.ilink.application.conversation;

import java.time.LocalDateTime;

/** 可被找回的会话摘要。 */
public record ChatSession(String sessionId, String userId, String title, String status,
                          LocalDateTime lastActiveTime, LocalDateTime createdTime) { }
