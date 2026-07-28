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
    private final UserSessionStore sessions;
    private final MemoryService memoryService;
    private final TodoService todoService;
    private final PlanSessionStore planSessions;
    private final WelcomeHandler welcomeHandler;
    private final ReplySender replySender;

    public CommandHandler(SessionService sessionService,
                          UserSessionStore sessions,
                          MemoryService memoryService,
                          TodoService todoService, PlanSessionStore planSessions,
                          WelcomeHandler welcomeHandler, ReplySender replySender) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.memoryService = memoryService;
        this.todoService = todoService;
        this.planSessions = planSessions;
        this.welcomeHandler = welcomeHandler;
        this.replySender = replySender;
    }

    public void handle(ReplyChannel client, String userId, CommandType command) throws Exception {
        switch (command) {
            case NEW_SESSION -> handleNewSession(client, userId);
            case SHOW_MEMORY -> handleShowMemory(client, userId);
            case SHOW_TASK -> handleShowTask(client, userId);
            case SHOW_PLAN -> handleShowPlan(client, userId);
            case LIST_SESSIONS -> handleListSessions(client, userId);
            case MENU, HELP -> welcomeHandler.sendMenu(client, userId);
            default -> { }
        }
    }

    private void handleNewSession(ReplyChannel client, String userId) throws Exception {
        sessionService.createNewSession(userId);
        replySender.sendReply(client, userId, "已开启新会话，让我们重新开始吧\uFF5E");
    }

    private void handleShowMemory(ReplyChannel client, String userId) throws Exception {
        String memory = memoryService.describe(userId);
        if (memory == null || memory.isBlank() || "你暂时还没有告诉我关于你的事情。".equals(memory)
                || "我还没有记住关于你的信息。".equals(memory)) {
            replySender.sendReply(client, userId, "你还没有告诉过我关于你的事情。你可以说\u201C记住我喜欢喝咖啡\u201D，我就会记住。");
        } else {
            replySender.sendReply(client, userId, memory);
        }
    }

    private void handleShowTask(ReplyChannel client, String userId) throws Exception {
        String tasks = todoService.list(userId);
        replySender.sendReply(client, userId, tasks);
    }

    private void handleShowPlan(ReplyChannel client, String userId) throws Exception {
        TaskPlan plan = planSessions.get(userId);
        if (plan == null) {
            replySender.sendReply(client, userId, "你目前还没有制定计划。你可以说\u201C帮我制定一个学习计划\u201D来创建计划。");
            return;
        }
        replySender.sendReply(client, userId, plan.toDisplayText());
    }

    private void handleListSessions(ReplyChannel client, String userId) throws Exception {
        java.util.List<MySqlStore.SessionRow> sessions = sessionService.listSessions(userId);
        if (sessions.isEmpty()) {
            replySender.sendReply(client, userId, "你还没有历史聊天记录。");
            return;
        }
        StringBuilder text = new StringBuilder("历史聊天记录：\n\n");
        int index = 1;
        for (MySqlStore.SessionRow row : sessions) {
            String title = row.title() == null || row.title().isBlank() ? "聊天 " + index : row.title();
            String status = "ACTIVE".equals(row.status()) ? " [当前]" : "";
            text.append(index).append(". ").append(title).append(status).append("\n");
            index++;
        }
        text.append("\n回复数字切换会话，或说\u201C查看历史聊天\u201D刷新");
        replySender.sendReply(client, userId, text.toString());
    }
}
