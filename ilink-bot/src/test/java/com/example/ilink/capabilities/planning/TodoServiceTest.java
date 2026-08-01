package com.example.ilink.capabilities.planning;

import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoServiceTest {

    private static final String USER = "todo-service-user";
    private CalendarEventStore calendarStore;
    private TodoService todoService;

    @BeforeEach
    void setUp() {
        calendarStore = new CalendarEventStore(false);
        todoService = new TodoService(new TodoStore(false),
                new CalendarService(calendarStore, new ReminderDeliveryStore(false)));
    }

    @Test
    void cancelsTodoByNaturalLanguageTitleAndTomorrowEvening() {
        TodoItem target = todoService.create(USER, "学习 Python 两小时",
                LocalDate.now().plusDays(1).atTime(20, 0), 30);
        todoService.create(USER, "学习 Python 两小时", LocalDate.now().plusDays(1).atTime(10, 0), 30);
        todoService.create(USER, "整理学习打卡记录", LocalDate.now().plusDays(1).atTime(20, 0), 30);

        String result = todoService.cancel(USER, "删除明天晚上的学习 Python 两小时待办");

        assertTrue(result.contains("学习 Python 两小时"));
        assertEquals("cancelled", todoService.items(USER).stream()
                .filter(todo -> todo.id().equals(target.id())).findFirst().orElseThrow().status());
        assertEquals("cancelled", calendarStore.get(target.calendarEventId()).status());
        assertEquals(2, todoService.activeItems(USER).size());
    }

    @Test
    void listsRecentBatchWithLinkedReminderTimes() {
        TodoItem first = todoService.create(USER, "学习 Python 两小时",
                LocalDate.now().plusDays(1).atTime(20, 0), 30);
        TodoItem second = todoService.create(USER, "整理学习记录",
                LocalDate.now().plusDays(2).atTime(10, 0), 15);

        String result = todoService.listRecentWithReminders(USER, List.of(first.id(), second.id()));

        assertTrue(result.startsWith("你刚才创建的待办和提醒安排："));
        assertTrue(result.contains("学习 Python 两小时"));
        assertTrue(result.contains("19:30"));
        assertTrue(result.contains("09:45"));
        assertTrue(result.contains("状态：待完成"));
    }

    @Test
    void reschedulesTodoAndItsLinkedReminder() {
        TodoItem todo = todoService.create(USER, "学习 Python 两小时",
                LocalDate.now().plusDays(1).atTime(20, 0), 30);

        String result = todoService.reschedule(USER, "把学习 Python 两小时待办改到后天晚上九点");

        assertTrue(result.contains("改期到"));
        TodoItem updated = todoService.items(USER).stream()
                .filter(item -> item.id().equals(todo.id())).findFirst().orElseThrow();
        assertEquals(LocalDate.now().plusDays(2).atTime(21, 0), updated.dueAt());
        assertEquals(LocalDate.now().plusDays(2).atTime(20, 30),
                calendarStore.get(todo.calendarEventId()).nextReminderAt());
    }

    @Test
    void completesTonightPythonTodoFromNaturalCompletionReport() {
        TodoItem target = todoService.create(USER, "学习 Python 两小时",
                LocalDate.now().atTime(20, 0), 30);
        todoService.create(USER, "规划下周健身计划", LocalDate.now().atTime(20, 0), 30);
        todoService.create(USER, "整理学习打卡记录", LocalDate.now().plusDays(1).atTime(10, 0), 30);

        TodoService.CompletionResult result = todoService.tryComplete(
                USER, "我已经完成今晚的 Python 学习任务，帮我记录完成情况。");

        assertTrue(result.matched());
        assertTrue(result.reply().contains("学习 Python 两小时"));
        assertEquals("completed", todoService.items(USER).stream()
                .filter(todo -> todo.id().equals(target.id())).findFirst().orElseThrow().status());
        assertEquals("completed", calendarStore.get(target.calendarEventId()).status());
        assertEquals(2, todoService.activeItems(USER).size());
    }

    @Test
    void asksUserWhenCompletionReportMatchesMultipleIndependentTodos() {
        todoService.create(USER, "学习 Python 两小时", LocalDate.now().atTime(20, 0), 30);
        todoService.create(USER, "复习 Python 语法", LocalDate.now().atTime(21, 0), 30);

        TodoService.CompletionResult result = todoService.tryComplete(
                USER, "我已经完成今晚的 Python 任务，帮我记录完成情况。");

        assertTrue(result.matched());
        assertTrue(result.reply().contains("多个符合条件的待办"));
        assertTrue(result.reply().contains("学习 Python 两小时"));
        assertTrue(result.reply().contains("复习 Python 语法"));
        assertEquals(2, todoService.activeItems(USER).size());
    }

    @Test
    void leavesLongTermPlanFallbackAvailableWhenNoTodoMatches() {
        todoService.create(USER, "整理学习打卡记录", LocalDate.now().atTime(20, 0), 30);

        TodoService.CompletionResult result = todoService.tryComplete(
                USER, "我已经完成今晚的 Python 学习任务，帮我记录完成情况。");

        assertEquals(false, result.matched());
        assertEquals(1, todoService.activeItems(USER).size());
    }
}
