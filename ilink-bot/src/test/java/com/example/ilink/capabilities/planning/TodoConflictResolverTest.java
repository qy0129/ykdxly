package com.example.ilink.capabilities.planning;

import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoConflictResolverTest {

    private static final String USER = "todo-conflict-user";
    private CalendarEventStore calendarStore;
    private TodoService todoService;
    private TodoConflictResolver resolver;

    @BeforeEach
    void setUp() {
        calendarStore = new CalendarEventStore(false);
        CalendarService calendarService = new CalendarService(calendarStore, new ReminderDeliveryStore(false));
        todoService = new TodoService(new TodoStore(false), calendarService);
        resolver = new TodoConflictResolver(todoService);
    }

    @Test
    void waitsForEveryConflictingDraftBeforeCreatingBatch() {
        LocalDateTime original = LocalDate.now().plusDays(3).atTime(10, 0);
        TodoConflictResolver.Resolution result = resolver.begin(USER, plan(
                draft("学习 Python", original),
                draft("规划健身", original),
                draft("整理记录", original)));

        assertFalse(result.completed());
        assertTrue(result.message().contains("有时间冲突"));
        assertTrue(todoService.items(USER).isEmpty());

        result = resolver.reply(USER, result.state(), "保留1");
        assertTrue(result.message().contains("规划健身"));
        result = resolver.reply(USER, result.state(), "明天下午3点");
        assertFalse(result.completed());
        assertTrue(result.message().contains("整理记录"));
        assertTrue(todoService.items(USER).isEmpty());

        result = resolver.reply(USER, result.state(), "后天下午4点");

        assertTrue(result.completed());
        assertEquals(3, result.created().size());
        assertEquals(3, todoService.activeItems(USER).size());
        assertEquals(LocalDate.now().plusDays(1).atTime(15, 0), result.created().get(1).dueAt());
        assertEquals(LocalDate.now().plusDays(2).atTime(16, 0), result.created().get(2).dueAt());
    }

    @Test
    void rejectsAnotherOccupiedTimeAndKeepsWaitingForNewTime() {
        LocalDateTime occupied = LocalDate.now().plusDays(1).atTime(15, 0);
        todoService.create(USER, "已有会议", occupied, 30);
        LocalDateTime original = LocalDate.now().plusDays(3).atTime(10, 0);
        TodoConflictResolver.Resolution result = resolver.begin(USER, plan(
                draft("任务甲", original), draft("任务乙", original)));
        result = resolver.reply(USER, result.state(), "保留1");

        result = resolver.reply(USER, result.state(), "明天下午3点");

        assertFalse(result.completed());
        assertTrue(result.message().contains("已有会议"));
        assertEquals(1, todoService.activeItems(USER).size());

        result = resolver.reply(USER, result.state(), "后天下午4点");
        assertTrue(result.completed());
        assertEquals(3, todoService.activeItems(USER).size());
    }

    @Test
    void canKeepNewDraftAndRescheduleExistingTodoWithCalendarReminder() {
        LocalDateTime original = LocalDate.now().plusDays(3).atTime(10, 0);
        TodoItem existing = todoService.create(USER, "已有复盘", original, 30);
        TodoConflictResolver.Resolution result = resolver.begin(USER, plan(draft("新建学习", original)));

        assertFalse(result.completed());
        assertTrue(result.message().contains("已有待办"));
        result = resolver.reply(USER, result.state(), "保留1");
        result = resolver.reply(USER, result.state(), "明天下午3点");

        assertTrue(result.completed());
        assertEquals(1, result.rescheduled().size());
        assertEquals(LocalDate.now().plusDays(1).atTime(15, 0), result.rescheduled().getFirst().dueAt());
        assertEquals(result.rescheduled().getFirst().dueAt(),
                calendarStore.get(existing.calendarEventId()).startAt());
    }

    @Test
    void unrelatedExistingConflictDoesNotBlockNewBatch() {
        LocalDateTime occupied = LocalDate.now().plusDays(2).atTime(9, 0);
        todoService.create(USER, "旧任务甲", occupied, 30);
        todoService.create(USER, "旧任务乙", occupied, 30);

        TodoConflictResolver.Resolution result = resolver.begin(USER,
                plan(draft("无关新任务", LocalDate.now().plusDays(4).atTime(11, 0))));

        assertTrue(result.completed());
        assertEquals(1, result.created().size());
    }

    @Test
    void acceptsNaturalKeepReplyAndStateSurvivesJsonRoundTrip() {
        LocalDateTime original = LocalDate.now().plusDays(3).atTime(10, 0);
        TodoConflictResolver.Resolution result = resolver.begin(USER,
                plan(draft("任务甲", original), draft("任务乙", original)));
        Gson gson = new Gson();
        TodoConflictState restored = gson.fromJson(gson.toJson(result.state()), TodoConflictState.class);

        assertTrue(resolver.acceptsReply(restored, "保留第一个"));
        result = resolver.reply(USER, restored, "保留任务甲");
        assertTrue(result.message().contains("任务乙"));
    }

    private TodoPlan plan(TodoDraft... drafts) {
        return new TodoPlan(List.of(drafts), 30, false, "", false);
    }

    private TodoDraft draft(String title, LocalDateTime dueAt) {
        return new TodoDraft(title, title, title, dueAt);
    }
}
