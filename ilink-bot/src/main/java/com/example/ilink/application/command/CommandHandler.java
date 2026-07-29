package com.example.ilink.application.command;

import com.example.ilink.application.conversation.SessionService;
import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.welcome.WelcomeHandler;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.platform.persistence.MySqlStore;

/** 系统命令的回复处理。 */
public final class CommandHandler {
    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final WelcomeHandler welcomeHandler;
    private final ReplySender replySender;

    public CommandHandler(SessionService sessionService, Object ignoredSessions, MemoryService memoryService,
                          TodoService ignoredTodoService, PlanSessionStore ignoredPlanSessions,
                          WelcomeHandler welcomeHandler, ReplySender replySender) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.welcomeHandler = welcomeHandler;
        this.replySender = replySender;
    }

    public void handle(ReplyChannel client, String userId, CommandType command) throws Exception {
        switch (command) {
            case NEW_SESSION -> {
                sessionService.createNewSession(userId);
                replySender.sendReply(client, userId, "已开启新会话。你的长期记忆、人格和常用地点仍会保留。");
            }
            case SHOW_MEMORY -> replySender.sendReply(client, userId, memoryService.describe(userId));
            case LIST_SESSIONS -> replySender.sendReply(client, userId, listSessions(userId));
            case MENU -> welcomeHandler.sendMenu(client, userId);
            case NONE -> { }
        }
    }

    public boolean trySwitchSession(ReplyChannel client, String userId, String text) throws Exception {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:切换会话|切换聊天)\\s*(\\d+)").matcher(text == null ? "" : text.trim());
        if (!matcher.matches()) return false;
        int index = Integer.parseInt(matcher.group(1));
        java.util.List<MySqlStore.SessionRow> items = sessionService.listSessions(userId);
        if (index < 1 || index > items.size()) {
            replySender.sendReply(client, userId, "没有这个会话序号，请先发送“历史会话”查看列表。");
            return true;
        }
        MySqlStore.SessionRow target = items.get(index - 1);
        if (sessionService.switchSession(userId, target.sessionId())) {
            replySender.sendReply(client, userId, "已切换到会话："
                    + (target.title() == null || target.title().isBlank() ? "聊天 " + index : target.title()));
        } else {
            replySender.sendReply(client, userId, "会话切换失败，请稍后重试。");
        }
        return true;
    }

    private String listSessions(String userId) {
        java.util.List<MySqlStore.SessionRow> items = sessionService.listSessions(userId);
        if (items.isEmpty()) return "暂无可找回的历史会话。";
        StringBuilder text = new StringBuilder("历史会话：\n");
        for (int i = 0; i < items.size(); i++) {
            MySqlStore.SessionRow item = items.get(i);
            String title = item.title() == null || item.title().isBlank() ? "聊天 " + (i + 1) : item.title();
            text.append(i + 1).append(". ").append(title)
                    .append("ACTIVE".equals(item.status()) ? " [当前]" : "").append('\n');
        }
        return text.append("\n发送“切换会话 序号”可继续历史聊天。").toString().trim();
    }
}
