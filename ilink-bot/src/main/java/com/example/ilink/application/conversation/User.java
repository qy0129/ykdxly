package com.example.ilink.application.conversation;

import java.time.LocalDateTime;

public record User(
        Long id,
        String wechatId,
        String nickname,
        LocalDateTime firstLoginTime,
        LocalDateTime lastLoginTime,
        LocalDateTime createdTime,
        LocalDateTime updatedTime) {
}
