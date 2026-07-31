package com.example.ilink.capabilities.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.planning.TodoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyReflectionServiceTest {

    private static final String USER = "reflection-user";
    private CalendarEventStore calendarStore;
    private CalendarService calendarService;
    private PlanSessionStore planSessions;
    private LifeStateStore lifeStates;
    private TodoService todoService;
    private DailyReflectionService service;

    @BeforeEach
    void setUp() {
        calendarStore = new CalendarEventStore(false);
        calendarService = new CalendarService(
                calendarStore, new ReminderDeliveryStore(false));
        planSessions = new PlanSessionStore(false);
        lifeStates = new LifeStateStore(false);
        todoService = new TodoService(new TodoStore(false), calendarService);
        service = new DailyReflectionService(planSessions, todoService, calendarService, lifeStates);
    }

    @Test
    void reusesSingleDailyReflectionEventWhenTimeChanges() {
        CalendarEvent first = service.ensureDailyReminder(USER, LocalTime.of(21, 30));
        CalendarEvent updated = service.ensureDailyReminder(USER, LocalTime.of(22, 0));

        assertEquals(first.id(), updated.id());
        assertEquals(1, calendarStore.all().size());
        assertEquals(LocalTime.of(22, 0), updated.startAt().toLocalTime());
        assertEquals("daily", updated.recurrence());
        assertEquals("life_reflection", updated.source());
    }

    @Test
    void reflectionCountsCompletedAndOverdueTodosFromRealState() {
        todoService.create(USER, "今日学习", LocalDate.now().atTime(20, 0), 30);
        todoService.complete(USER, "今日学习");
        todoService.create(USER, "逾期整理", LocalDate.now().minusDays(1).atTime(18, 0), 30);

        DailyReflection reflection = service.buildAndSave(USER, LocalDate.now());

        assertEquals(1, reflection.planned());
        assertEquals(1, reflection.completed());
        assertEquals(1, reflection.overdue());
        assertEquals(0, reflection.pending());
    }

    @Test
    void enrichesVerifiedStatisticsWithModelInsight() {
        todoService.create(USER, "学习 Python", LocalDate.now().atTime(20, 0), 30);
        todoService.complete(USER, "学习 Python");
        todoService.create(USER, "整理记录", LocalDate.now().atTime(21, 0), 30);
        ReflectionInsightService insights = new ReflectionInsightService(body -> """
                {"summary":"晚间学习执行稳定，但整理任务仍需拆分。",
                 "highlights":["完成了 Python 学习"],"problems":["整理记录尚未完成"],
                 "patterns":["明确时间的学习任务更稳定"],
                 "suggestions":["把整理记录拆成两个 25 分钟步骤"],
                 "tomorrow_focus":"先完成第一批记录整理"}
                """);
        service = new DailyReflectionService(planSessions, todoService,
                calendarService, lifeStates, insights);

        DailyReflection reflection = service.buildAndSave(USER, LocalDate.now());

        assertEquals(2, reflection.planned());
        assertEquals(1, reflection.completed());
        assertEquals(1, reflection.pending());
        assertTrue(reflection.aiGenerated());
        assertTrue(reflection.completedItems().getFirst().contains("学习 Python"));
        assertTrue(reflection.unfinishedItems().getFirst().contains("整理记录"));
        assertTrue(reflection.toDisplayText().contains("把整理记录拆成两个 25 分钟步骤"));
        assertTrue(reflection.toDisplayText().contains("今日计划 2 项，完成 1 项"));
    }

    @Test
    void modelFailureStillReturnsAndSavesRuleReflection() {
        todoService.create(USER, "整理记录", LocalDate.now().atTime(21, 0), 30);
        ReflectionInsightService failing = new ReflectionInsightService(body -> {
            throw new IllegalStateException("timeout");
        });
        service = new DailyReflectionService(planSessions, todoService,
                calendarService, lifeStates, failing);

        DailyReflection reflection = service.buildAndSave(USER, LocalDate.now());

        assertEquals(1, reflection.planned());
        assertTrue(!reflection.aiGenerated());
        assertTrue(reflection.toDisplayText().contains("仍有计划未完成"));
        assertEquals(1, lifeStates.reflections(USER).size());
    }
}
