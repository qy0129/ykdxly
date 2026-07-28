package com.example.ilink.capabilities.calendar;

import com.example.ilink.platform.persistence.MySqlStore;

import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.ReminderDelivery;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 提醒投递存储和领取状态机。 */
public final class ReminderDeliveryStore {

    private final Map<String, ReminderDelivery> deliveries = new ConcurrentHashMap<>();
    private final MySqlStore database = MySqlStore.getInstance();

    public ReminderDeliveryStore() {
        for (ReminderDelivery delivery : database.loadActiveReminderDeliveries()) {
            deliveries.put(delivery.id(), delivery);
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
        return deliveries.values().stream()
                .filter(delivery -> isDue(delivery, now))
                .filter(delivery -> allowedUsers == null || allowedUsers.contains(delivery.userId()))
                .sorted(Comparator.comparing(ReminderDelivery::scheduledAt))
                .limit(100)
                .map(delivery -> {
                    ReminderDelivery claimed = delivery.claiming(now);
                    save(claimed);
                    return claimed;
                })
                .toList();
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
        deliveries.put(delivery.id(), delivery);
        database.saveReminderDelivery(delivery);
    }
}
