package com.example.ilink.capabilities.calendar;

import com.example.ilink.platform.persistence.MySqlStore;

import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.ReminderDelivery;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 提醒投递存储和领取状态机。 */
public final class ReminderDeliveryStore {

    private final Map<String, ReminderDelivery> deliveries = new ConcurrentHashMap<>();
    private final NavigableMap<LocalDateTime, Set<String>> dueIndex = new TreeMap<>();
    private final MySqlStore database = MySqlStore.getInstance();

    public ReminderDeliveryStore() {
        for (ReminderDelivery delivery : database.loadActiveReminderDeliveries()) {
            deliveries.put(delivery.id(), delivery);
            addToDueIndex(delivery);
        }
    }

    public synchronized void schedule(CalendarEvent event) {
        if (event.nextReminderAt() == null || !"active".equals(event.status())) return;
        String dedupKey = event.id() + "|" + event.nextReminderAt();
        boolean exists = deliveries.values().stream()
                .anyMatch(delivery -> dedupKey.equals(delivery.dedupKey())
                        && !"cancelled".equals(delivery.status()));
        if (exists || database.reminderDeliveryExists(dedupKey)) return;
        ReminderDelivery delivery = new ReminderDelivery(UUID.randomUUID().toString(), event.id(), event.userId(),
                event.nextReminderAt(), "pending", 0, null, null, "", dedupKey, null);
        save(delivery);
    }

    /** 领取到期投递并设置短租约，进程异常退出后仍可再次领取。 */
    public synchronized List<ReminderDelivery> claimDue(LocalDateTime now) {
        return claimDue(now, null);
    }

    /** 只领取当前具备可发送会话上下文的用户，避免离线用户进入失败重试。 */
    public synchronized List<ReminderDelivery> claimDue(LocalDateTime now, Set<String> allowedUsers) {
        List<String> dueIds = new ArrayList<>(100);
        outer:
        for (Set<String> ids : dueIndex.headMap(now, true).values()) {
            for (String id : ids) {
                ReminderDelivery delivery = deliveries.get(id);
                if (delivery == null || !isDue(delivery, now)) continue;
                if (allowedUsers != null && !allowedUsers.contains(delivery.userId())) continue;
                dueIds.add(id);
                if (dueIds.size() == 100) break outer;
            }
        }

        List<ReminderDelivery> claimedDeliveries = new ArrayList<>(dueIds.size());
        for (String id : dueIds) {
            ReminderDelivery claimed = deliveries.get(id).claiming(now);
            save(claimed);
            claimedDeliveries.add(claimed);
        }
        return claimedDeliveries;
    }

    /** 登录或用户重新出现时立即领取其全部逾期提醒，不受旧失败退避时间影响。 */
    public synchronized List<ReminderDelivery> claimOverdueForUser(String userId, LocalDateTime now) {
        return deliveries.values().stream()
                .filter(delivery -> userId.equals(delivery.userId()))
                .filter(delivery -> !delivery.scheduledAt().isAfter(now))
                .filter(delivery -> switch (delivery.status()) {
                    case "pending", "failed" -> true;
                    case "sending" -> delivery.lockedUntil() == null || !delivery.lockedUntil().isAfter(now);
                    default -> false;
                })
                .sorted(Comparator.comparing(ReminderDelivery::scheduledAt))
                .limit(100)
                .map(delivery -> {
                    ReminderDelivery claimed = delivery.claiming(now);
                    save(claimed);
                    return claimed;
                })
                .toList();
    }

    public synchronized void markSent(ReminderDelivery delivery, LocalDateTime now) {
        save(delivery.sent(now));
    }

    public synchronized void markFailed(ReminderDelivery delivery, LocalDateTime now, String error) {
        save(delivery.failed(now, error));
    }

    public synchronized void cancelByEvent(String eventId) {
        deliveries.values().stream()
                .filter(delivery -> eventId.equals(delivery.eventId())
                        && !"sent".equals(delivery.status()) && !"cancelled".equals(delivery.status()))
                .toList()
                .forEach(delivery -> save(delivery.cancelled()));
    }

    private boolean isDue(ReminderDelivery delivery, LocalDateTime now) {
        return switch (delivery.status()) {
            case "pending" -> !delivery.scheduledAt().isAfter(now);
            case "failed" -> delivery.nextRetryAt() == null || !delivery.nextRetryAt().isAfter(now);
            case "sending" -> delivery.lockedUntil() == null || !delivery.lockedUntil().isAfter(now);
            default -> false;
        };
    }

    private void save(ReminderDelivery delivery) {
        ReminderDelivery previous = deliveries.put(delivery.id(), delivery);
        removeFromDueIndex(previous);
        addToDueIndex(delivery);
        database.saveReminderDelivery(delivery);
    }

    private void addToDueIndex(ReminderDelivery delivery) {
        LocalDateTime dueAt = effectiveDueAt(delivery);
        if (dueAt == null) return;
        dueIndex.computeIfAbsent(dueAt, ignored -> new HashSet<>()).add(delivery.id());
    }

    private void removeFromDueIndex(ReminderDelivery delivery) {
        if (delivery == null) return;
        LocalDateTime dueAt = effectiveDueAt(delivery);
        if (dueAt == null) return;
        Set<String> ids = dueIndex.get(dueAt);
        if (ids == null) return;
        ids.remove(delivery.id());
        if (ids.isEmpty()) dueIndex.remove(dueAt);
    }

    private LocalDateTime effectiveDueAt(ReminderDelivery delivery) {
        return switch (delivery.status()) {
            case "pending" -> delivery.scheduledAt();
            case "failed" -> delivery.nextRetryAt() == null
                    ? delivery.scheduledAt() : delivery.nextRetryAt();
            case "sending" -> delivery.lockedUntil() == null
                    ? delivery.scheduledAt() : delivery.lockedUntil();
            default -> null;
        };
    }
}
