package com.example.ilink.capabilities.calendar;

import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.ReminderDelivery;
import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.ReminderDeliveryStore;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
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
        return create(userId, title, type, startAt, recurrence, reminderMinutes, "");
    }

    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                String recurrence, int reminderMinutes, String notes) {
        return create(userId, title, type, startAt, recurrence, reminderMinutes, notes, "", "");
    }

    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                String recurrence, int reminderMinutes, String notes,
                                String groupId, String source) {
        LocalDateTime reminderAt = startAt.minusMinutes(Math.max(0, reminderMinutes));
        long leadMinutes = Math.max(0, Duration.between(reminderAt, startAt).toMinutes());
        int normalizedReminderMinutes = leadMinutes > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) leadMinutes;
        CalendarEvent event = new CalendarEvent(UUID.randomUUID().toString(), userId, title, type,
                startAt, reminderAt, recurrence, recurrenceAnchor(recurrence, startAt),
                normalizedReminderMinutes, "active", groupId, source, notes, LocalDateTime.now());
        store.save(event);
        reminderStore.schedule(event);
        return event;
    }

    /** 使用已经解析完成的事件时间和提醒时间创建记录，支持秒级提醒。 */
    public CalendarEvent create(String userId, String title, String type, LocalDateTime startAt,
                                LocalDateTime reminderAt, String recurrence) {
        long leadMinutes = Math.max(0, Duration.between(reminderAt, startAt).toMinutes());
        int reminderMinutes = leadMinutes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) leadMinutes;
        CalendarEvent event = new CalendarEvent(UUID.randomUUID().toString(), userId, title, type,
                startAt, reminderAt, recurrence, recurrenceAnchor(recurrence, startAt),
                reminderMinutes, "active", "", "", "", LocalDateTime.now());
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
            if (!event.notes().isBlank()) reply.append("   ").append(event.notes()).append('\n');
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

    public List<CalendarEvent> findActive(String userId, String titleKeyword, LocalDate day) {
        return store.findActive(userId, titleKeyword, day);
    }

    public CalendarEvent findById(String eventId) {
        return eventId == null || eventId.isBlank() ? null : store.get(eventId);
    }

    public String completeById(String eventId) {
        CalendarEvent event = store.get(eventId);
        if (event == null || !"active".equals(event.status())) return "这条日历事件已经不存在或已结束。";
        complete(eventId);
        return "好，这件事已经替你标记完成了：“" + event.title() + "”。";
    }

    public String cancelById(String eventId) {
        CalendarEvent event = store.get(eventId);
        if (event == null || !"active".equals(event.status())) return "这条日历事件已经不存在或已结束。";
        cancel(eventId);
        return "好的，已经替你取消“" + event.title() + "”，之后不会再提醒。";
    }

    public String postponeById(String eventId, int minutes) {
        CalendarEvent event = store.get(eventId);
        if (event == null || event.nextReminderAt() == null || !"active".equals(event.status())) {
            return "这条日历事件当前没有可延后的提醒。";
        }
        CalendarEvent updated = event.withNextReminderAt(event.nextReminderAt().plusMinutes(minutes));
        reminderStore.cancelByEvent(event.id());
        store.save(updated);
        reminderStore.schedule(updated);
        return "好的，已把“" + event.title() + "”的提醒延后 " + minutes + " 分钟，将在 "
                + updated.nextReminderAt().format(TIME_FORMAT) + " 提醒你。";
    }

    public int cancelGroup(String userId, String source, String groupId) {
        List<CalendarEvent> events = store.findActiveGroup(userId, source, groupId);
        events.forEach(event -> cancel(event.id()));
        return events.size();
    }

    public int cancelLatestGroupBySource(String userId, String source) {
        CalendarEvent latest = store.findActive(userId, "", null).stream()
                .filter(event -> source.equals(event.source()) && !event.groupId().isBlank())
                .max(java.util.Comparator.comparing(CalendarEvent::createdAt))
                .orElse(null);
        return latest == null ? 0 : cancelGroup(userId, source, latest.groupId());
    }

    public CalendarEvent reschedule(String eventId, String title, LocalDateTime startAt) {
        CalendarEvent event = store.get(eventId);
        if (event == null) return null;
        reminderStore.cancelByEvent(eventId);
        LocalDateTime reminderAt = startAt.minusMinutes(Math.max(0, event.reminderMinutes()));
        CalendarEvent updated = event.withDetails(title, startAt, reminderAt).withStatus("active");
        store.save(updated);
        reminderStore.schedule(updated);
        return updated;
    }

    public List<CalendarEvent> eventsForDay(String userId, LocalDate day) {
        return eventsBetween(userId, day, day);
    }

    /** 返回闭区间日期范围内的有效事件，供周计划和日报页面使用。 */
    public List<CalendarEvent> eventsBetween(String userId, LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        List<CalendarEvent> occurrences = new ArrayList<>();
        for (CalendarEvent event : store.list(userId)) {
            if ("active".equals(event.status())) occurrences.addAll(occurrencesBetween(event, from, to));
        }
        occurrences.sort(Comparator.comparing(CalendarEvent::startAt));
        return occurrences;
    }

    static List<CalendarEvent> occurrencesBetween(CalendarEvent event, LocalDate from, LocalDate to) {
        List<CalendarEvent> occurrences = new ArrayList<>();
        LocalDateTime startAt = event.startAt();
        while (startAt.toLocalDate().isBefore(from)) {
            startAt = advanceOccurrence(startAt, event.recurrence(), event.recurrenceAnchor());
            if (startAt == null) return occurrences;
        }
        while (!startAt.toLocalDate().isAfter(to)) {
            occurrences.add(event.withSchedule(startAt, startAt.minusMinutes(event.reminderMinutes())));
            startAt = advanceOccurrence(startAt, event.recurrence(), event.recurrenceAnchor());
            if (startAt == null) break;
        }
        return occurrences;
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
        Occurrence next = nextOccurrence(event, delivery.scheduledAt(), now);
        CalendarEvent updated = next == null
                ? event.withNextReminderAt(null)
                : event.withSchedule(next.startAt(), next.reminderAt());
        store.save(updated);
        if (next != null) reminderStore.schedule(updated);
    }

    public void releaseReminder(ReminderDelivery delivery) {
        reminderStore.release(delivery);
    }

    public void markReminderFailed(ReminderDelivery delivery, LocalDateTime now, String error) {
        reminderStore.markFailed(delivery, now, error);
    }

    private Occurrence nextOccurrence(CalendarEvent event, LocalDateTime reminderAt, LocalDateTime now) {
        if ("none".equals(event.recurrence())) return null;
        Duration leadTime = Duration.between(reminderAt, event.startAt());
        LocalDateTime nextStart = advanceOccurrence(event.startAt(), event.recurrence(), event.recurrenceAnchor());
        LocalDateTime nextReminder = nextStart.minus(leadTime);
        while (nextReminder != null && !nextReminder.isAfter(now)) {
            nextStart = advanceOccurrence(nextStart, event.recurrence(), event.recurrenceAnchor());
            nextReminder = nextStart.minus(leadTime);
        }
        return nextReminder == null ? null : new Occurrence(nextStart, nextReminder);
    }

    static LocalDateTime advanceOccurrence(LocalDateTime value, String recurrence, String anchor) {
        if (value == null) return null;
        return switch (recurrence) {
            case "daily" -> value.plusDays(1);
            case "weekly" -> value.plusWeeks(1);
            case "monthly" -> nextMonthly(value, anchor);
            case "yearly" -> nextYearly(value, anchor);
            default -> null;
        };
    }

    private static LocalDateTime nextMonthly(LocalDateTime value, String anchor) {
        int day = parseAnchorPart(anchor, 0, value.getDayOfMonth());
        YearMonth month = YearMonth.from(value).plusMonths(1);
        return month.atDay(Math.min(day, month.lengthOfMonth())).atTime(value.toLocalTime());
    }

    private static LocalDateTime nextYearly(LocalDateTime value, String anchor) {
        int monthValue = parseAnchorPart(anchor, 0, value.getMonthValue());
        int day = parseAnchorPart(anchor, 1, value.getDayOfMonth());
        YearMonth month = YearMonth.of(value.getYear() + 1, monthValue);
        return month.atDay(Math.min(day, month.lengthOfMonth())).atTime(value.toLocalTime());
    }

    private static int parseAnchorPart(String anchor, int index, int fallback) {
        if (anchor == null || anchor.isBlank()) return fallback;
        String[] parts = anchor.split("-");
        try {
            return Integer.parseInt(parts[Math.min(index, parts.length - 1)]);
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static String recurrenceAnchor(String recurrence, LocalDateTime startAt) {
        if ("monthly".equals(recurrence)) return Integer.toString(startAt.getDayOfMonth());
        if ("yearly".equals(recurrence)) return startAt.getMonthValue() + "-" + startAt.getDayOfMonth();
        return "";
    }

    private record Occurrence(LocalDateTime startAt, LocalDateTime reminderAt) {
    }
}
