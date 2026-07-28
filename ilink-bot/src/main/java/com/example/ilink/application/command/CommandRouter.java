package com.example.ilink.application.command;

/**
 * 系统命令路由器。
 *
 * <p>纯规则判断用户输入是否为系统指令，不调用LLM。若匹配系统指令则返回对应
 * {@link CommandType}，否则返回 {@link CommandType#NONE} 表示需要进入Agent流程。</p>
 */
public final class CommandRouter {

    public CommandType route(String message) {
        if (message == null || message.isBlank()) return CommandType.NONE;
        String text = message.strip();

        if ("1".equals(text)) return CommandType.NEW_SESSION;
        if ("2".equals(text)) return CommandType.SHOW_MEMORY;
        if ("3".equals(text)) return CommandType.SHOW_TASK;
        if ("4".equals(text)) return CommandType.SHOW_PLAN;

        if (text.matches("新聊天|新会话|重新开始|重置会话")) return CommandType.NEW_SESSION;
        if (text.matches("我的记忆|你记得我什么|记得我的什么|我的偏好")) return CommandType.SHOW_MEMORY;
        if (text.matches("我的任务|待办|任务列表|我有什么任务|查看待办")) return CommandType.SHOW_TASK;
        if (text.matches("我的计划|查看计划|计划列表|当前计划")) return CommandType.SHOW_PLAN;
        if (text.matches("查看历史聊天|历史聊天|历史会话|切换会话|切换聊天")) return CommandType.LIST_SESSIONS;
        if (text.matches("菜单|帮助|help|功能|指令|/help|/menu|/start")) return CommandType.MENU;

        return CommandType.NONE;
    }
}
