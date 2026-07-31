package com.example.ilink.capabilities.planning;

import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

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
}
