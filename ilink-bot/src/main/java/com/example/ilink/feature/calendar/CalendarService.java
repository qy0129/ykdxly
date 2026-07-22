package com.example.ilink.feature.calendar;

import com.example.ilink.model.CalendarEvent;
import com.example.ilink.storage.CalendarEventStore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** 提供事件创建、查询、完成和提醒领取等日历领域操作。 */
public final class CalendarService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final CalendarEventStore store;

    public CalendarService(CalendarEventStore store) {
        this.store = store;
    }

    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                String recurrence, int reminderMinutes) {
        LocalDateTime reminderAt = startAt.minusMinutes(Math.max(0, reminderMinutes));
        CalendarEvent event = new CalendarEvent(UUID.randomUUID().toString(), userId, title, type,
                startAt, reminderAt, recurrence, Math.max(0, reminderMinutes), "active", "", LocalDateTime.now());
        store.save(event);
        return event;
    }

    public String listForDay(String userId, LocalDate day) {
        List<CalendarEvent> events = store.list(userId).stream()
                .filter(event -> event.startAt().toLocalDate().equals(day) && "active".equals(event.status()))
                .toList();
        if (events.isEmpty()) {
            return day.equals(LocalDate.now()) ? "你今天暂时没有安排。" : "这一天暂时没有安排。";
        }
        StringBuilder reply = new StringBuilder(day.equals(LocalDate.now()) ? "你今天的安排：\n" : "当天安排：\n");
        for (int index = 0; index < events.size(); index++) {
            CalendarEvent event = events.get(index);
            reply.append(index + 1).append(". ").append(event.startAt().format(TIME_FORMAT))
                    .append(" ").append(event.title()).append("（").append(event.type()).append("）\n");
        }
        return reply.toString().trim();
    }

    public String completeLatest(String userId) {
        CalendarEvent event = store.latestActive(userId);
        if (event == null) return "当前没有可完成的日历事件。";
        store.save(event.withStatus("completed").withNextReminderAt(null));
        return "已完成：" + event.title();
    }

    /** 取消最近一条未完成事件，保留记录以便后续审计而非物理删除。 */
    public String cancelLatest(String userId) {
        CalendarEvent event = store.latestActive(userId);
        if (event == null) return "当前没有可取消的日历事件。";
        store.save(event.withStatus("cancelled").withNextReminderAt(null));
        return "已取消：" + event.title();
    }

    public String postponeLatest(String userId, int minutes) {
        CalendarEvent event = store.latestActive(userId);
        if (event == null || event.nextReminderAt() == null) return "当前没有可延后的提醒。";
        CalendarEvent updated = event.withNextReminderAt(event.nextReminderAt().plusMinutes(minutes));
        store.save(updated);
        return "已将“" + event.title() + "”延后 " + minutes + " 分钟，"
                + "将在 " + updated.nextReminderAt().format(TIME_FORMAT) + " 提醒你。";
    }

    public List<CalendarEvent> claimDueEvents(LocalDateTime now) {
        return store.claimDue(now);
    }
}
