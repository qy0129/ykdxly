package com.example.ilink.feature.calendar;

import com.example.ilink.model.CalendarEvent;
import com.example.ilink.model.ReminderDelivery;
import com.example.ilink.storage.CalendarEventStore;
import com.example.ilink.storage.ReminderDeliveryStore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 提供事件创建、查询、完成和提醒领取等日历领域操作。 */
public final class CalendarService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("M月d日 HH:mm");
    private final CalendarEventStore store;
    private final ReminderDeliveryStore reminderStore;

    public CalendarService(CalendarEventStore store) {
        this(store, new ReminderDeliveryStore());
    }

    public CalendarService(CalendarEventStore store, ReminderDeliveryStore reminderStore) {
        this.store = store;
        this.reminderStore = reminderStore;
        for (CalendarEvent event : store.all()) reminderStore.schedule(event);
    }

    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                String recurrence, int reminderMinutes) {
        LocalDateTime reminderAt = startAt.minusMinutes(Math.max(0, reminderMinutes));
        return create(userId, title, type, startAt, reminderAt, recurrence);
    }

    /** 使用已经解析完成的事件时间和提醒时间创建记录，支持秒级提醒。 */
    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                LocalDateTime reminderAt, String recurrence) {
        long leadMinutes = Math.max(0, Duration.between(reminderAt, startAt).toMinutes());
        int reminderMinutes = leadMinutes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) leadMinutes;
        CalendarEvent event = new CalendarEvent(UUID.randomUUID().toString(), userId, title, type,
                startAt, reminderAt, recurrence, reminderMinutes, "active", "", LocalDateTime.now());
        store.save(event);
        reminderStore.schedule(event);
        return event;
    }

    public String listForDay(String userId, LocalDate day) {
        List<CalendarEvent> events = eventsForDay(userId, day);
        if (events.isEmpty()) {
            return day.equals(LocalDate.now())
                    ? "今天暂时没有日历安排，可以安心按自己的节奏来。"
                    : "这一天暂时没有日历安排，可以留一点时间给自己。";
        }
        StringBuilder reply = new StringBuilder(day.equals(LocalDate.now())
                ? "我帮你看了一下，今天有这些安排：\n"
                : "我帮你整理好了，这一天有这些安排：\n");
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
        reminderStore.cancelByEvent(event.id());
        return "好，这件事已经替你标记完成了：“" + event.title() + "”。辛苦了。";
    }

    /** 取消最近一条未完成事件，保留记录以便后续审计而非物理删除。 */
    public String cancelLatest(String userId) {
        CalendarEvent event = store.latestActive(userId);
        if (event == null) return "当前没有可取消的日历事件。";
        store.save(event.withStatus("cancelled").withNextReminderAt(null));
        reminderStore.cancelByEvent(event.id());
        return "好的，已经替你取消“" + event.title() + "”，之后不会再为这件事提醒你。";
    }

    public String postponeLatest(String userId, int minutes) {
        CalendarEvent event = store.latestActive(userId);
        if (event == null || event.nextReminderAt() == null) return "当前没有可延后的提醒。";
        CalendarEvent updated = event.withNextReminderAt(event.nextReminderAt().plusMinutes(minutes));
        reminderStore.cancelByEvent(event.id());
        store.save(updated);
        reminderStore.schedule(updated);
        return "好的，先替你把“" + event.title() + "”往后放 " + minutes + " 分钟。"
                + "我会在 " + updated.nextReminderAt().format(TIME_FORMAT) + " 再来提醒你。";
    }

    public List<CalendarEvent> eventsForDay(String userId, LocalDate day) {
        return store.list(userId).stream()
                .filter(event -> event.startAt().toLocalDate().equals(day) && "active".equals(event.status()))
                .toList();
    }

    /** 返回闭区间日期范围内的有效事件，供周计划和日报页面使用。 */
    public List<CalendarEvent> eventsBetween(String userId, LocalDate from, LocalDate to) {
        return store.list(userId).stream()
                .filter(event -> "active".equals(event.status()))
                .filter(event -> {
                    LocalDate date = event.startAt().toLocalDate();
                    return !date.isBefore(from) && !date.isAfter(to);
                })
                .toList();
    }

    public void complete(String eventId) {
        CalendarEvent event = store.get(eventId);
        if (event != null) {
            store.save(event.withStatus("completed").withNextReminderAt(null));
            reminderStore.cancelByEvent(eventId);
        }
    }

    public void cancel(String eventId) {
        CalendarEvent event = store.get(eventId);
        if (event != null) {
            store.save(event.withStatus("cancelled").withNextReminderAt(null));
            reminderStore.cancelByEvent(eventId);
        }
    }

    public List<ReminderDelivery> claimDueReminders(LocalDateTime now) {
        return reminderStore.claimDue(now);
    }

    public List<ReminderDelivery> claimDueReminders(LocalDateTime now, Set<String> allowedUsers) {
        return reminderStore.claimDue(now, allowedUsers);
    }

    public List<ReminderDelivery> claimOverdueRemindersForUser(String userId, LocalDateTime now) {
        return reminderStore.claimOverdueForUser(userId, now);
    }

    public CalendarEvent getEvent(String eventId) {
        return store.get(eventId);
    }

    /** 发送成功后才推进周期事件并创建下一次投递。 */
    public void markReminderSent(ReminderDelivery delivery, LocalDateTime now) {
        reminderStore.markSent(delivery, now);
        CalendarEvent event = store.get(delivery.eventId());
        if (event == null) return;
        Occurrence next = nextOccurrence(event.startAt(), delivery.scheduledAt(), event.recurrence(), now);
        CalendarEvent updated = next == null
                ? event.withNextReminderAt(null)
                : event.withSchedule(next.startAt(), next.reminderAt());
        store.save(updated);
        if (next != null) reminderStore.schedule(updated);
    }

    public void markReminderFailed(ReminderDelivery delivery, LocalDateTime now, String error) {
        reminderStore.markFailed(delivery, now, error);
    }

    private Occurrence nextOccurrence(LocalDateTime startAt, LocalDateTime reminderAt,
                                      String recurrence, LocalDateTime now) {
        if ("none".equals(recurrence)) return null;
        LocalDateTime nextStart = advance(startAt, recurrence);
        LocalDateTime nextReminder = advance(reminderAt, recurrence);
        while (nextReminder != null && !nextReminder.isAfter(now)) {
            nextStart = advance(nextStart, recurrence);
            nextReminder = advance(nextReminder, recurrence);
        }
        return nextReminder == null ? null : new Occurrence(nextStart, nextReminder);
    }

    private LocalDateTime advance(LocalDateTime value, String recurrence) {
        if (value == null) return null;
        return switch (recurrence) {
            case "daily" -> value.plusDays(1);
            case "weekly" -> value.plusWeeks(1);
            case "monthly" -> value.plusMonths(1);
            case "yearly" -> value.plusYears(1);
            default -> null;
        };
    }

    private record Occurrence(LocalDateTime startAt, LocalDateTime reminderAt) {
    }
}
