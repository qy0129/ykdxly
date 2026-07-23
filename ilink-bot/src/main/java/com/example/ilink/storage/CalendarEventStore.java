package com.example.ilink.storage;

import com.example.ilink.model.CalendarEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 日历事件的统一存储入口。MySQL 可用时同步持久化；未启用数据库时保留进程内可用的降级存储。
 */
public final class CalendarEventStore {

    private final Map<String, CalendarEvent> events = new ConcurrentHashMap<>();
    private final MySqlStore database = MySqlStore.getInstance();

    public CalendarEventStore() {
        for (CalendarEvent event : database.loadCalendarEvents()) {
            events.put(event.id(), event);
        }
    }

    public synchronized void save(CalendarEvent event) {
        events.put(event.id(), event);
        database.saveCalendarEvent(event);
    }

    public synchronized List<CalendarEvent> list(String userId) {
        return events.values().stream()
                .filter(event -> event.userId().equals(userId))
                .sorted(Comparator.comparing(CalendarEvent::startAt))
                .toList();
    }

    public synchronized CalendarEvent latestActive(String userId) {
        return events.values().stream()
                .filter(event -> event.userId().equals(userId) && "active".equals(event.status()))
                .max(Comparator.comparing(CalendarEvent::createdAt))
                .orElse(null);
    }

    /**
     * 领取到期提醒时先推进下一次触发时间。即使调度器再次扫描，也不会重复发送同一条提醒。
     */
    public synchronized List<CalendarEvent> claimDue(LocalDateTime now) {
        List<CalendarEvent> due = new ArrayList<>();
        for (CalendarEvent event : events.values()) {
            if (!"active".equals(event.status()) || event.nextReminderAt() == null
                    || event.nextReminderAt().isAfter(now)) {
                continue;
            }
            due.add(event);
            save(event.withNextReminderAt(nextOccurrence(event.nextReminderAt(), event.recurrence(), now)));
        }
        due.sort(Comparator.comparing(CalendarEvent::nextReminderAt));
        return due;
    }

    private LocalDateTime nextOccurrence(LocalDateTime current, String recurrence, LocalDateTime now) {
        LocalDateTime next = switch (recurrence) {
            case "daily" -> current.plusDays(1);
            case "weekly" -> current.plusWeeks(1);
            case "monthly" -> current.plusMonths(1);
            case "yearly" -> current.plusYears(1);
            default -> null;
        };
        while (next != null && !next.isAfter(now)) {
            next = switch (recurrence) {
                case "daily" -> next.plusDays(1);
                case "weekly" -> next.plusWeeks(1);
                case "monthly" -> next.plusMonths(1);
                case "yearly" -> next.plusYears(1);
                default -> null;
            };
        }
        return next;
    }
}
