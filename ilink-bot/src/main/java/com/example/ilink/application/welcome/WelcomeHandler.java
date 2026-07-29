package com.example.ilink.application.welcome;

import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.conversation.User;
import com.example.ilink.platform.persistence.UserRepository;

public final class WelcomeHandler {

    private static final String MENU_TEXT = """
            🤖 ClawBot 助手

            请选择：

            1️⃣ 新聊天
            2️⃣ 我的记忆
            3️⃣ 我的任务
            4️⃣ 查看计划

            回复数字即可""";

    private static final String WELCOME_TEXT = """
            欢迎使用 ClawBot 助手～ 🎉

            我可以帮你：
            • 日常聊天
            • 查询天气
            • 查快递、管理待办
            • 制定学习/工作计划
            • 图片分析、生成图表文档

            回复"菜单"随时查看功能列表。""";

    private final UserRepository userRepository;

    public WelcomeHandler(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    /** 处理首次登录：创建用户 → 发送欢迎 + 菜单。返回 true 表示首次登录。 */
    public boolean handleFirstLogin(ReplyChannel client, String userId) throws Exception {
        User existing = userRepository.findByWechatId(userId);
        if (existing != null) {
            userRepository.updateLastLoginTime(userId);
            return false;
        }
        userRepository.save(userId, null);
        client.sendText(userId, WELCOME_TEXT);
        client.sendText(userId, MENU_TEXT);
        return true;
    }

    public void sendMenu(ReplyChannel client, String userId) throws Exception {
        client.sendText(userId, MENU_TEXT);
    }

    public void sendWelcome(ReplyChannel client, String userId) throws Exception {
        client.sendText(userId, WELCOME_TEXT);
    }
}
