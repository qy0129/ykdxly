package com.example.ilink.application.command;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.application.conversation.SessionService;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.ReplyChannel;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.welcome.WelcomeHandler;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.platform.persistence.MySqlStore;

public final class CommandHandler {

    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final TodoService todoService;
    private final PlanSessionStore planSessions;
    private final WelcomeHandler welcomeHandler;
    private final ReplySender replySender;

    public CommandHandler(SessionService sessionService,
                          UserSessionStore ignoredSessions,
                          MemoryService memoryService,
                          TodoService todoService,
                          PlanSessionStore planSessions,
                          WelcomeHandler welcomeHandler,
                          ReplySender replySender) {
        this.sessionService = sessionService;
        this.memoryService = memoryService;
        this.todoService = todoService;
        this.planSessions = planSessions;
        this.welcomeHandler = welcomeHandler;
        this.replySender = replySender;
    }

    public void handle(ReplyChannel client, String userId, CommandType command) throws Exception {
        switch (command) {
            case NEW_SESSION -> {
                sessionService.createNewSession(userId);
                replySender.sendReply(client, userId, "已开启新会话，长期记忆和常用地点仍会保留。");
            }
            case SHOW_MEMORY -> replySender.sendReply(client, userId, memoryService.describe(userId));
            case SHOW_TASK -> replySender.sendReply(client, userId, todoService.list(userId));
            case SHOW_PLAN -> {
                TaskPlan plan = planSessions.get(userId);
                replySender.sendReply(client, userId,
                        plan == null ? "你目前还没有制定计划。" : plan.toDisplayText());
            }
            case LIST_SESSIONS -> handleListSessions(client, userId);
            case HELP, MENU -> welcomeHandler.sendMenu(client, userId);
            case NONE -> { }
        }
    }

    public boolean trySwitchSession(ReplyChannel client, String userId, String text) throws Exception {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?:切换会话|切换聊天)\\s*(\\d+)")
                .matcher(text == null ? "" : text.trim());
        if (!matcher.matches()) return false;

        int index = Integer.parseInt(matcher.group(1));
        java.util.List<MySqlStore.SessionRow> items = sessionService.listSessions(userId);
        if (index < 1 || index > items.size()) {
            replySender.sendReply(client, userId, "没有这个会话序号，请先查看历史会话。");
            return true;
        }

        MySqlStore.SessionRow target = items.get(index - 1);
        if (sessionService.switchSession(userId, target.sessionId())) {
            String title = target.title() == null || target.title().isBlank()
                    ? "聊天 " + index : target.title();
            replySender.sendReply(client, userId, "已切换到会话：" + title);
        } else {
            replySender.sendReply(client, userId, "会话切换失败，请稍后重试。");
        }
        return true;
    }

    private void handleListSessions(ReplyChannel client, String userId) throws Exception {
        java.util.List<MySqlStore.SessionRow> items = sessionService.listSessions(userId);
        if (items.isEmpty()) {
            replySender.sendReply(client, userId, "暂无历史会话。");
            return;
        }
        StringBuilder text = new StringBuilder("历史会话：\n\n");
        for (int i = 0; i < items.size(); i++) {
            MySqlStore.SessionRow row = items.get(i);
            String title = row.title() == null || row.title().isBlank() ? "聊天 " + (i + 1) : row.title();
            text.append(i + 1).append(". ").append(title)
                    .append("ACTIVE".equals(row.status()) ? " [当前]" : "").append('\n');
        }
        text.append("\n发送“切换会话 序号”可继续历史聊天。");
        replySender.sendReply(client, userId, text.toString());
    }
}
