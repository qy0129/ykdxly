package com.example.ilink.application.inbox;

import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import com.example.ilink.capabilities.inbox.InboxModule;
import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.application.routing.IntentAction;
import com.example.ilink.application.routing.IntentPlan;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.application.routing.MessageMode;
import com.google.gson.Gson;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.planning.TodoStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboxApplicationServiceTest {

    @Test
    void doesNotCreateTodoWithoutModelDecision() {
        CalendarService calendar = new CalendarService(
                new CalendarEventStore(false), new ReminderDeliveryStore(false));
        TodoService todos = new TodoService(new TodoStore(false), calendar);
        InboxApplicationService service = new InboxApplicationService(
                new InboxModule(InboxConfig.defaultConfig()), todos, calendar);

        var forwarded = service.handle("u1", "m1", Instant.now(), "PRIVATE",
                "课程通知：请在明天下午3点前提交高数作业，完成后上传学习平台。");
        var command = service.handle("u1", "m2", Instant.now(), "PRIVATE",
                "帮我查一下上海的 Java 实习岗位");

        assertFalse(forwarded.consumed());
        assertTrue(todos.activeItems("u1").isEmpty());
        assertFalse(command.consumed());
    }

    @Test
    void passesExplicitPlanningCalendarAndDocumentRequests() {
        CalendarService calendar = new CalendarService(
                new CalendarEventStore(false), new ReminderDeliveryStore(false));
        TodoService todos = new TodoService(new TodoStore(false), calendar);
        InboxApplicationService service = new InboxApplicationService(
                new InboxModule(InboxConfig.defaultConfig()), todos, calendar);

        var schedule = service.handle("u1", "m1", Instant.now(), "PRIVATE",
                "按照这个安排帮我生成日程安排");
        var combined = service.handle("u1", "m2", Instant.now(), "PRIVATE",
                "需要你帮我创建日历提醒，生成学习文档，定制每日学习计划");
        var studyPlan = service.handle("u1", "m3", Instant.now(), "PRIVATE",
                "帮我制定一个 python 学习计划");

        assertFalse(schedule.consumed());
        assertFalse(combined.consumed());
        assertFalse(studyPlan.consumed());
        assertTrue(todos.activeItems("u1").isEmpty());
    }

    @Test
    void createsOnlyModelExtractedTodoForPassiveMessage() {
        CalendarService calendar = new CalendarService(
                new CalendarEventStore(false), new ReminderDeliveryStore(false));
        TodoService todos = new TodoService(new TodoStore(false), calendar);
        InboxApplicationService service = new InboxApplicationService(
                new InboxModule(InboxConfig.defaultConfig()), todos, calendar);
        IntentResult todoRoute = new Gson().fromJson(
                "{\"intent\":\"todo\",\"calendar_time\":\"\"}", IntentResult.class);
        IntentPlan passive = new IntentPlan(List.of(
                new IntentAction("r1", "提交高数作业", List.of(), todoRoute)),
                MessageMode.PASSIVE_MESSAGE);

        var result = service.handle("u1", "m1", Instant.now(), "PRIVATE",
                "课程通知：请在明天下午三点前提交高数作业", passive);

        assertTrue(result.consumed());
        assertEquals(1, todos.activeItems("u1").size());
        assertEquals("提交高数作业", todos.activeItems("u1").getFirst().title());
    }

    @Test
    void passiveChatWithoutInboxActionsIsNotConsumed() {
        CalendarService calendar = new CalendarService(
                new CalendarEventStore(false), new ReminderDeliveryStore(false));
        TodoService todos = new TodoService(new TodoStore(false), calendar);
        InboxApplicationService service = new InboxApplicationService(
                new InboxModule(InboxConfig.defaultConfig()), todos, calendar);
        IntentPlan passiveChat = new IntentPlan(List.of(
                new IntentAction("r1", "只是转发一段说明", List.of(), IntentResult.chat())),
                MessageMode.PASSIVE_MESSAGE);

        var result = service.handle("u1", "m-chat", Instant.now(), "PRIVATE",
                "只是转发一段说明", passiveChat);

        assertFalse(result.consumed());
        assertTrue(todos.activeItems("u1").isEmpty());
    }
}
