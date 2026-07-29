package com.example.ilink.application.conversation;

import java.time.LocalDateTime;

/** 数据库用户记录。 */
public record User(Long id, String wechatId, String nickname, LocalDateTime firstLoginTime,
                   LocalDateTime lastLoginTime, LocalDateTime createdTime, LocalDateTime updatedTime) { }
