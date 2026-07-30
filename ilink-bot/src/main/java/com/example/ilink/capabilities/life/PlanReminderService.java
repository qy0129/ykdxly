package com.example.ilink.capabilities.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** 将计划任务同步到既有日历和提醒投递链路。 */
public final class PlanReminderService {

    private final PlanSessionStore planSessions;
    private final CalendarService calendarService;

    public PlanReminderService(PlanSessionStore planSessions, CalendarService calendarService) {
        this.planSessions = planSessions;
        this.calendarService = calendarService;
    }

    public int sync(String userId, TaskPlan plan, LocalTime reminderTime) {
        int changed = 0;
        LocalDate horizon = LocalDate.now().plusDays(7);
        for (PlanTask task : plan.tasks()) {
            String eventId = planSessions.calendarEventIdForTask(task.id());
            LocalDateTime startAt = eventTime(task, reminderTime);
            if ("completed".equals(task.status()) || startAt == null || !startAt.isAfter(LocalDateTime.now())
                    || startAt.toLocalDate().isAfter(horizon)) {
                if (!eventId.isBlank()) {
                    calendarService.cancel(eventId);
                    planSessions.unlinkTaskFromCalendar(task.id());
                    changed++;
                }
                continue;
            }
            if (eventId.isBlank()) {
                CalendarEvent event = calendarService.create(userId, task.title(), "学习", startAt,
                        "none", 0, task.description(), plan.id(), "study_plan");
                planSessions.linkTaskToCalendar(task.id(), event.id());
            } else {
                calendarService.reschedule(eventId, task.title(), startAt);
            }
            changed++;
        }
        return changed;
    }

    public void complete(PlanTask task) {
        String eventId = planSessions.calendarEventIdForTask(task.id());
        if (!eventId.isBlank()) {
            calendarService.complete(eventId);
            planSessions.unlinkTaskFromCalendar(task.id());
        }
    }

    private LocalDateTime eventTime(PlanTask task, LocalTime reminderTime) {
        if (task.scheduledDate().isBlank()) return null;
        try {
            return LocalDateTime.of(LocalDate.parse(task.scheduledDate()), reminderTime);
        } catch (RuntimeException error) {
            return null;
        }
    }
}
