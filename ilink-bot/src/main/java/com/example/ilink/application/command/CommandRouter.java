package com.example.ilink.application.command;

/** 不经过模型的系统命令识别。 */
public final class CommandRouter {
    public CommandType route(String message) {
        if (message == null || message.isBlank()) return CommandType.NONE;
        String text = message.strip();
<<<<<<< Updated upstream
        if (text.matches("新聊天|新会话|重新开始|重置会话|/new")) return CommandType.NEW_SESSION;
        if (text.matches("我的记忆|你记得我什么|记得我的什么|我的偏好")) return CommandType.SHOW_MEMORY;
        if (text.matches("查看历史聊天|历史聊天|历史会话|切换会话|切换聊天")) return CommandType.LIST_SESSIONS;
        if (text.matches("菜单|帮助|help|功能|指令|/help|/menu|/start")) return CommandType.MENU;
=======

        if (text.matches("\\u65B0\\u804A\\u5929|\\u65B0\\u4F1A\\u8BDD|\\u91CD\\u65B0\\u5F00\\u59CB|\\u91CD\\u7F6E\\u4F1A\\u8BDD|/new")) {
            return CommandType.NEW_SESSION;
        }
        if (text.matches("\\u6211\\u7684\\u8BB0\\u5FC6|\\u4F60\\u8BB0\\u5F97\\u6211\\u4EC0\\u4E48|\\u8BB0\\u5F97\\u6211\\u7684\\u4EC0\\u4E48|\\u6211\\u7684\\u504F\\u597D")) {
            return CommandType.SHOW_MEMORY;
        }
        if (text.matches("\\u6211\\u7684\\u4EFB\\u52A1|\\u5F85\\u529E|\\u4EFB\\u52A1\\u5217\\u8868|\\u6211\\u6709\\u4EC0\\u4E48\\u4EFB\\u52A1|\\u67E5\\u770B\\u5F85\\u529E")) {
            return CommandType.SHOW_TASK;
        }
        if (text.matches("\\u6211\\u7684\\u8BA1\\u5212|\\u67E5\\u770B\\u8BA1\\u5212|\\u8BA1\\u5212\\u5217\\u8868|\\u5F53\\u524D\\u8BA1\\u5212")) {
            return CommandType.SHOW_PLAN;
        }
        if (text.matches("\\u67E5\\u770B\\u5386\\u53F2\\u804A\\u5929|\\u5386\\u53F2\\u804A\\u5929|\\u5386\\u53F2\\u4F1A\\u8BDD|\\u5207\\u6362\\u4F1A\\u8BDD|\\u5207\\u6362\\u804A\\u5929")) {
            return CommandType.LIST_SESSIONS;
        }
>>>>>>> Stashed changes
        return CommandType.NONE;
    }
}
