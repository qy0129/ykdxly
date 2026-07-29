package com.example.ilink.capabilities.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderDeliveryStoreTest {

    @Test
    void claimsOnlyDueRemindersForAllowedUser() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 28, 10, 0);
        String suffix = UUID.randomUUID().toString();
        String allowedUser = "user-a-" + suffix;
        String dueEvent = "due-" + suffix;
        ReminderDeliveryStore store = new ReminderDeliveryStore();
        store.schedule(event(dueEvent, allowedUser, now.minusMinutes(1)));
        store.schedule(event("future-" + suffix, allowedUser, now.plusMinutes(1)));
        store.schedule(event("other-user-" + suffix, "user-b-" + suffix, now.minusMinutes(2)));

        List<ReminderDelivery> claimed = store.claimDue(now, Set.of(allowedUser));

        assertEquals(1, claimed.size());
        assertEquals(dueEvent, claimed.getFirst().eventId());
        assertTrue(store.claimDue(now, Set.of(allowedUser)).isEmpty());
    }

    private CalendarEvent event(String id, String userId, LocalDateTime reminderAt) {
        return new CalendarEvent(id, userId, id, "测试", reminderAt.plusMinutes(10), reminderAt,
                "none", "", 10, "active", "", "test", "", reminderAt.minusHours(1));
    }
}
