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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class CommandHandler {

    private static final Pattern EXPLICIT_SESSION_SWITCH = Pattern.compile(
            "(?:切换会话|切换聊天)\\s*(\\d+)");
    private static final Pattern SESSION_LIST_REQUEST = Pattern.compile(
            "(?:查看历史聊天|历史聊天|历史会话|切换会话|切换聊天)");

    private final SessionService sessionService;
    private final MemoryService memoryService;
    private final TodoService todoService;
    private final PlanSessionStore planSessions;
    private final WelcomeHandler welcomeHandler;
    private final ReplySender replySender;
    private final UserSessionStore sessions;
    private final SessionSelectionContext sessionSelection = new SessionSelectionContext();

    public CommandHandler(SessionService sessionService,
                          UserSessionStore sessions,
                          MemoryService memoryService,
                          TodoService todoService,
                          PlanSessionStore planSessions,
                          WelcomeHandler welcomeHandler,
                          ReplySender replySender) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.memoryService = memoryService;
        this.todoService = todoService;
        this.planSessions = planSessions;
        this.welcomeHandler = welcomeHandler;
        this.replySender = replySender;
    }

    public void handle(ReplyChannel client, String userId, CommandType command,
                       boolean allowBareSessionSelection) throws Exception {
        if (command != CommandType.LIST_SESSIONS) sessionSelection.clear(userId);
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
            case LIST_SESSIONS -> handleListSessions(client, userId, allowBareSessionSelection);
            case HELP, MENU -> welcomeHandler.sendMenu(client, userId);
            case NONE -> { }
        }
    }

    public boolean trySwitchSession(ReplyChannel client, String userId, String text,
                                    boolean allowBareSessionSelection,
                                    boolean hasPendingBusinessInteraction) throws Exception {
        String normalized = text == null ? "" : text.trim();
        Matcher explicit = EXPLICIT_SESSION_SWITCH.matcher(normalized);
        if (explicit.matches()) {
            sessionSelection.clear(userId);
            return switchSession(client, userId, Integer.parseInt(explicit.group(1)));
        }
        if (!allowBareSessionSelection) return false;
        if (normalized.matches("\\d+")) {
            if (!sessionSelection.permitsBareNumber(userId,
                    sessions.getCurrentSession(userId).sessionId(), hasPendingBusinessInteraction)) return false;
            return switchSession(client, userId, Integer.parseInt(normalized));
        }
        if (!SESSION_LIST_REQUEST.matcher(normalized).matches()) {
            sessionSelection.clear(userId);
        }
        return false;
    }

    private boolean switchSession(ReplyChannel client, String userId, int index) throws Exception {
        java.util.List<MySqlStore.SessionRow> items = sessionService.listSessions(userId);
        if (index < 1 || index > items.size()) {
            replySender.sendReply(client, userId, "没有这个会话序号，请先查看历史会话。");
            return true;
        }

        MySqlStore.SessionRow target = items.get(index - 1);
        if (sessionService.switchSession(userId, target.sessionId())) {
            sessionSelection.clear(userId);
            String title = target.title() == null || target.title().isBlank()
                    ? "聊天 " + index : target.title();
            replySender.sendReply(client, userId, "已切换到会话：" + title);
        } else {
            replySender.sendReply(client, userId, "会话切换失败，请稍后重试。");
        }
        return true;
    }

    private void handleListSessions(ReplyChannel client, String userId,
                                    boolean allowBareSessionSelection) throws Exception {
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
        if (allowBareSessionSelection) {
            sessionSelection.open(userId, sessions.getCurrentSession(userId).sessionId());
            text.append("\n5 分钟内回复会话序号，或发送“切换会话 序号”可继续历史聊天。");
        } else {
            text.append("\n请使用左侧会话列表切换聊天。");
        }
        replySender.sendReply(client, userId, text.toString());
    }
}
