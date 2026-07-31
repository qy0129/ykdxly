package com.example.ilink.application.inbox;

import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.inbox.InboxModule;
import com.example.ilink.capabilities.inbox.model.MessageResult;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.model.RawMessage;
import com.example.ilink.application.routing.IntentAction;
import com.example.ilink.application.routing.IntentPlan;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.planning.DateTimeParser;
import com.example.ilink.capabilities.planning.TodoItem;
import com.example.ilink.capabilities.planning.TodoService;

import java.time.Instant;
import java.time.LocalDateTime;
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

    /** 兼容旧调用；没有大模型分析结果时不自动创建待办。 */
    public HandleResult handle(String userId, String messageId, Instant receivedAt,
                               String sourceType, String text) {
        return HandleResult.pass();
    }

    /** 仅处理大模型判定为被动通知的todo和日历动作。 */
    public HandleResult handle(String userId, String messageId, Instant receivedAt,
                               String sourceType, String text, IntentPlan plan) {
        if (text == null || text.isBlank() || plan == null || !plan.isPassiveMessage()) {
            return HandleResult.pass();
        }
        List<IntentAction> inboxActions = plan.actions().stream()
                .filter(action -> "todo".equals(action.route().intent())
                        || "calendar_event".equals(action.route().intent()))
                .toList();
        if (inboxActions.isEmpty()) return HandleResult.pass();
        RawMessage raw = new RawMessage(messageId, userId, userId, text, receivedAt,
                sourceType(sourceType), "");
        MessageResult result = inbox.process(raw);
        if (!result.isSuccess()) return HandleResult.pass();
        if (result.isDuplicate()) return new HandleResult(true, "");

        List<String> created = new ArrayList<>();
        for (IntentAction action : inboxActions) {
            IntentResult route = action.route();
            if ("todo".equals(route.intent())) {
                LocalDateTime dueAt = parseTime(route.calendarTime(), action.requestText());
                TodoItem todo = todos.create(userId, concise(action.requestText()), dueAt,
                        dueAt == null ? 0 : 30);
                created.add("待办：" + todo.title() + formatTime(todo.dueAt()));
            } else if ("calendar_event".equals(route.intent())) {
                LocalDateTime startAt = parseTime(route.calendarTime(), action.requestText());
                if (startAt == null) continue;
                String title = route.calendarTitle().isBlank()
                        ? concise(action.requestText()) : concise(route.calendarTitle());
                String recurrence = route.calendarRecurrence().isBlank()
                        ? "none" : route.calendarRecurrence();
                calendar.create(userId, title, "通知", startAt, recurrence,
                        Math.max(0, route.calendarReminderMinutes()),
                        "由大模型从被动通知中识别");
                created.add("日历：" + title + formatTime(startAt));
            }
        }
        if (created.isEmpty()) {
            return HandleResult.pass();
        }

        String response = "已从这条通知中识别并安排：\n- " + String.join("\n- ", created);
        return new HandleResult(true, response);
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

    private static LocalDateTime parseTime(String preferred, String fallback) {
        LocalDateTime value = DateTimeParser.parse(preferred);
        return value == null ? DateTimeParser.parse(fallback) : value;
    }

    public record HandleResult(boolean consumed, String response) {
        public static HandleResult pass() {
            return new HandleResult(false, "");
        }
    }
}
