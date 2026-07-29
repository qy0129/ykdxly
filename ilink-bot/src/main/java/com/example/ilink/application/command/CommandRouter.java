package com.example.ilink.application.command;

public final class CommandRouter {

    public CommandType route(String message) {
        if (message == null || message.isBlank()) return CommandType.NONE;
        String text = message.strip();

        if ("1".equals(text)) return CommandType.NEW_SESSION;
        if ("2".equals(text)) return CommandType.SHOW_MEMORY;
        if ("3".equals(text)) return CommandType.SHOW_TASK;
        if ("4".equals(text)) return CommandType.SHOW_PLAN;

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
        if (text.matches("\\u83DC\\u5355|\\u5E2E\\u52A9|help|\\u529F\\u80FD|\\u6307\\u4EE4|/help|/menu|/start")) {
            return CommandType.MENU;
        }
        return CommandType.NONE;
    }
}
