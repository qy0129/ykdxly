package com.example.ilink.application.inbox;

import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import com.example.ilink.capabilities.inbox.InboxModule;
import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.planning.TodoStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxApplicationServiceTest {

    @Test
    void createsTodoForForwardedTaskButPassesAgentCommand() {
        CalendarService calendar = new CalendarService(
                new CalendarEventStore(false), new ReminderDeliveryStore(false));
        TodoService todos = new TodoService(new TodoStore(false), calendar);
        InboxApplicationService service = new InboxApplicationService(
                new InboxModule(InboxConfig.defaultConfig()), todos, calendar);

        var forwarded = service.handle("u1", "m1", Instant.now(), "PRIVATE",
                "课程通知：请在明天下午3点前提交高数作业，完成后上传学习平台。");
        var command = service.handle("u1", "m2", Instant.now(), "PRIVATE",
                "帮我查一下上海的 Java 实习岗位");

        assertTrue(forwarded.consumed());
        assertEquals(1, todos.activeItems("u1").size());
        assertFalse(command.consumed());
    }
}
