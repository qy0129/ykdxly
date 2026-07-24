package com.example.ilink.feature.planning;

import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.model.TodoItem;
import com.example.ilink.storage.TodoStore;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** 待办创建、查询、完成和取消服务。 */
public final class TodoService {

    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final TodoStore store;
    private final CalendarService calendarService;

    public TodoService(TodoStore store, CalendarService calendarService) {
        this.store = store;
        this.calendarService = calendarService;
    }

    public TodoItem create(String userId, String title, LocalDateTime dueAt, int reminderMinutes) {
        CalendarEvent event = dueAt == null ? null : calendarService.create(
                userId, title, "待办", dueAt, "none", reminderMinutes);
        LocalDateTime now = LocalDateTime.now();
        TodoItem todo = new TodoItem(UUID.randomUUID().toString(), userId, title, dueAt, "pending",
                event == null ? "" : event.id(), now, now);
        store.save(todo);
        return todo;
    }

    public String list(String userId) {
        List<TodoItem> active = activeItems(userId);
        if (active.isEmpty()) return "你目前没有待完成事项。";
        StringBuilder text = new StringBuilder("你的待办：\n");
        for (int index = 0; index < active.size(); index++) {
            TodoItem todo = active.get(index);
            text.append(index + 1).append(". ").append(todo.title());
            if (todo.dueAt() != null) text.append("（").append(todo.dueAt().format(DISPLAY_TIME)).append("）");
            text.append('\n');
        }
        return text.toString().trim();
    }

    /** 返回用户全部待办记录，供日报页面计算完成进度。 */
    public List<TodoItem> items(String userId) {
        return store.list(userId);
    }

    /** 返回按截止时间排序的未完成待办。 */
    public List<TodoItem> activeItems(String userId) {
        return store.list(userId).stream()
                .filter(todo -> "pending".equals(todo.status()))
                .toList();
    }

    /** 按稳定 ID 完成待办，避免网页上存在同名事项时误操作。 */
    public boolean completeById(String userId, String todoId) {
        TodoItem todo = store.list(userId).stream()
                .filter(item -> item.id().equals(todoId) && "pending".equals(item.status()))
                .findFirst().orElse(null);
        if (todo == null) return false;
        store.save(todo.withStatus("completed"));
        if (!todo.calendarEventId().isBlank()) calendarService.complete(todo.calendarEventId());
        return true;
    }

    public String completeLatest(String userId) {
        return complete(userId, "");
    }

    public String complete(String userId, String keyword) {
        TodoItem todo = findActive(userId, keyword);
        if (todo == null) return "当前没有可以完成的待办。";
        store.save(todo.withStatus("completed"));
        if (!todo.calendarEventId().isBlank()) calendarService.complete(todo.calendarEventId());
        return "已完成待办：" + todo.title();
    }

    public String cancelLatest(String userId) {
        return cancel(userId, "");
    }

    public String cancel(String userId, String keyword) {
        TodoItem todo = findActive(userId, keyword);
        if (todo == null) return "当前没有可以取消的待办。";
        store.save(todo.withStatus("cancelled"));
        if (!todo.calendarEventId().isBlank()) calendarService.cancel(todo.calendarEventId());
        return "已取消待办：" + todo.title();
    }

    private TodoItem findActive(String userId, String keyword) {
        if (keyword == null || keyword.isBlank()) return store.latestActive(userId);
        return store.list(userId).stream()
                .filter(todo -> "pending".equals(todo.status()) && todo.title().contains(keyword.trim()))
                .findFirst().orElse(null);
    }
}
