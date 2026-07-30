package com.example.ilink.application.inbox;

import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.inbox.InboxModule;
import com.example.ilink.capabilities.inbox.model.ExtractedTask;
import com.example.ilink.capabilities.inbox.model.MessageResult;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.model.RawMessage;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.capabilities.planning.TodoService;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 将 Inbox 的结构化结果落到 Todo 和日历。 */
public final class InboxApplicationService {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final InboxModule inbox;
    private final TodoService todos;
    private final CalendarService calendar;

    public InboxApplicationService(InboxModule inbox, TodoService todos, CalendarService calendar) {
        this.inbox = inbox;
        this.todos = todos;
        this.calendar = calendar;
    }

    /** consumed=true 表示消息已由 Inbox 完整处理，不应再次进入通用对话路由。 */
    public HandleResult handle(String userId, String messageId, Instant receivedAt,
                               String sourceType, String text) {
        if (text == null || text.isBlank() || looksLikeAgentCommand(text)) return HandleResult.pass();
        RawMessage raw = new RawMessage(messageId, userId, userId, text, receivedAt,
                sourceType(sourceType), "");
        MessageResult result = inbox.process(raw);
        if (!result.isSuccess()) return HandleResult.pass();
        if (result.isDuplicate()) return new HandleResult(true, "");

        List<String> created = new ArrayList<>();
        if (result.hasTasks()) {
            LocalDateTime extractedTime = result.extraction().times().stream()
                    .map(value -> value.resolvedAt()).findFirst().orElse(null);
            for (ExtractedTask task : result.extraction().tasks()) {
                LocalDateTime dueAt = task.deadline() == null ? extractedTime : task.deadline();
                TodoItem todo = todos.create(userId, concise(task.title()), dueAt, dueAt == null ? 0 : 30);
                created.add("待办：" + todo.title() + formatTime(todo.dueAt()));
            }
        } else if (result.summary().messageType() == MessageSummary.MessageType.NOTIFICATION
                && result.hasTimes()) {
            LocalDateTime startAt = result.extraction().times().get(0).resolvedAt();
            String title = concise(result.summary().summary());
            calendar.create(userId, title, "通知", startAt, "none", 30,
                    "由 Inbox Agent 从微信消息中提取");
            created.add("日历：" + title + formatTime(startAt));
        }
        if (created.isEmpty()) return HandleResult.pass();

        String response = "已整理这条消息：\n" + result.summary().summary().trim()
                + "\n\n已安排：\n- " + String.join("\n- ", created);
        return new HandleResult(true, response);
    }

    private static boolean looksLikeAgentCommand(String text) {
        String value = text.strip();
        return value.matches("^(帮我|请你|给我|我要|我想|你能|能不能|可以帮我|查一下|搜索|总结一下).*?");
    }

    private static ProcessedMessage.SourceType sourceType(String value) {
        try {
            return ProcessedMessage.SourceType.valueOf(value == null ? "PRIVATE" : value.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return ProcessedMessage.SourceType.OTHER;
        }
    }

    private static String concise(String value) {
        String text = value == null ? "待处理事项" : value.strip();
        return text.length() <= 120 ? text : text.substring(0, 120);
    }

    private static String formatTime(LocalDateTime value) {
        return value == null ? "" : "（" + value.format(TIME) + "）";
    }

    public record HandleResult(boolean consumed, String response) {
        public static HandleResult pass() {
            return new HandleResult(false, "");
        }
    }
}
