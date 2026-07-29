package com.example.ilink.application.welcome;

import com.example.ilink.application.messaging.ReplyChannel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 每次启动后只向首次出现的用户发送一次欢迎消息。 */
public final class WelcomeHandler {
    private final Set<String> greetedUsers = ConcurrentHashMap.newKeySet();

    public WelcomeHandler(Object ignoredRepository) { }

    public void handleFirstLogin(ReplyChannel client, String userId) throws Exception {
        if (greetedUsers.add(userId)) {
            client.sendText(userId, "你好，可以直接告诉我需要做什么。发送“新会话”可开始独立聊天。");
        }
    }
}
