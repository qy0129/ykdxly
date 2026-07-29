package com.example.ilink.application.welcome;

import com.example.ilink.application.messaging.ReplyChannel;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 轻量欢迎与帮助菜单。 */
public final class WelcomeHandler {
    private final Set<String> greetedUsers = ConcurrentHashMap.newKeySet();

<<<<<<< Updated upstream
    public WelcomeHandler(Object ignoredRepository) { }

    public void sendMenu(ReplyChannel client, String userId) throws Exception {
        client.sendText(userId, "可以直接提问或使用：新会话、历史会话、我的记忆。");
    }

    public void handleFirstLogin(ReplyChannel client, String userId) throws Exception {
        if (greetedUsers.add(userId)) {
            client.sendText(userId, "你好，可以直接告诉我需要做什么。发送“新会话”可开始独立聊天。");
        }
=======
    private final UserRepository userRepository;

    public WelcomeHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 首次登录只登记用户，不发送会干扰正常对话的菜单消息。 */
    public boolean handleFirstLogin(ReplyChannel client, String userId) throws Exception {
        User existing = userRepository.findByWechatId(userId);
        if (existing != null) {
            userRepository.updateLastLoginTime(userId);
            return false;
        }
        userRepository.save(userId, null);
        return true;
>>>>>>> Stashed changes
    }
}
