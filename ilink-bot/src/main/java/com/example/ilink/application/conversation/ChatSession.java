package com.example.ilink.application.conversation;

import java.time.LocalDateTime;

public record ChatSession(
        String sessionId,
        String userId,
        String title,
        String status,
        LocalDateTime lastActiveTime,
        LocalDateTime createdTime) {
}
