package com.example.ilink.storage;

import com.example.ilink.model.CalendarEvent;

import java.time.LocalDateTime;
import java.time.LocalDate;
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

    public synchronized CalendarEvent get(String eventId) {
        return events.get(eventId);
    }

    public synchronized List<CalendarEvent> findActive(String userId, String titleKeyword, LocalDate day) {
        String keyword = titleKeyword == null ? "" : titleKeyword.trim();
        return events.values().stream()
                .filter(event -> event.userId().equals(userId) && "active".equals(event.status()))
                .filter(event -> keyword.isBlank() || event.title().contains(keyword))
                .filter(event -> day == null || event.startAt().toLocalDate().equals(day))
                .sorted(Comparator.comparing(CalendarEvent::startAt))
                .toList();
    }

    public synchronized List<CalendarEvent> findActiveGroup(String userId, String source, String groupId) {
        return events.values().stream()
                .filter(event -> event.userId().equals(userId) && "active".equals(event.status()))
                .filter(event -> source.equals(event.source()) && groupId.equals(event.groupId()))
                .toList();
    }

    public synchronized List<CalendarEvent> all() {
        return List.copyOf(events.values());
    }
}
