package com.example.ilink.capabilities.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TaskPlanningService;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskCheckinServiceTest {

    @Test
    void shouldCompleteCurrentTaskAndPersistActivity() {
        Fixture fixture = new Fixture();
        TaskPlan plan = fixture.plan();
        fixture.sessions.set(fixture.userId, plan);

        TaskCheckinService.CheckinResult result = fixture.service.checkIn(fixture.userId,
                "完成了 " + plan.tasks().getFirst().id());

        assertTrue(result.success());
        assertEquals("completed", fixture.sessions.get(fixture.userId).tasks().getFirst().status());
        assertEquals(1, fixture.states.activities(fixture.userId).size());
    }

    @Test
    void shouldReplanAfterDelay() {
        Fixture fixture = new Fixture();
        TaskPlan plan = fixture.plan();
        fixture.sessions.set(fixture.userId, plan);

        TaskCheckinService.CheckinResult result = fixture.service.checkIn(fixture.userId,
                "延期 " + plan.tasks().getFirst().id());

        assertTrue(result.success());
        LocalDate shifted = LocalDate.parse(fixture.sessions.get(fixture.userId).tasks().getFirst().scheduledDate());
        assertFalse(shifted.isBefore(LocalDate.now().plusDays(1)));
    }

    private static final class Fixture {
        private final String userId = "life-test-" + UUID.randomUUID();
        private final PlanSessionStore sessions = new PlanSessionStore(false);
        private final LifeStateStore states = new LifeStateStore(false);
        private final TaskPlanningService planning = new TaskPlanningService(HttpClient.newHttpClient());
        private final PlanReminderService reminders = new PlanReminderService(
                sessions, new CalendarService(new CalendarEventStore(false), new ReminderDeliveryStore(false)));
        private final TaskCheckinService service = new TaskCheckinService(sessions, planning, reminders, states);

        private TaskPlan plan() {
            return planning.createPlan("学习测试", LocalDate.now().plusDays(3), "每天1小时", List.of(
                    new PlanTask("1", "第一节", "", 60, "high", "", "pending"),
                    new PlanTask("2", "第二节", "", 60, "medium", "", "pending")));
        }
    }
}
