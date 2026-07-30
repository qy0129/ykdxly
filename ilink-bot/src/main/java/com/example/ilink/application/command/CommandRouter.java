package com.example.ilink.application.command;

/** 不经过模型的系统命令识别。 */
public final class CommandRouter {
    public CommandType route(String message) {
        if (message == null || message.isBlank()) return CommandType.NONE;
        String text = message.strip();
        if (text.matches("新聊天|新会话|重新开始|重置会话|/new")) return CommandType.NEW_SESSION;
        if (text.matches("我的记忆|你记得我什么|记得我的什么|我的偏好")) return CommandType.SHOW_MEMORY;
        if (text.matches("我的任务|待办|任务列表|我有什么任务|查看待办")) return CommandType.SHOW_TASK;
        if (text.matches("我的计划|查看计划|计划列表|当前计划")) return CommandType.SHOW_PLAN;
        if (text.matches("查看历史聊天|历史聊天|历史会话|切换会话|切换聊天")) {
            return CommandType.LIST_SESSIONS;
        }
        return CommandType.NONE;
    }
}
