package com.example.ilink.capabilities.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReminderDeliveryTest {

    @Test
    void failureUsesIncreasingRetryDelayAndSuccessClearsLease() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 10, 0);
        ReminderDelivery delivery = new ReminderDelivery("1", "event", "user", now,
                "pending", 0, null, null, "", "event|time", null);

        ReminderDelivery firstFailure = delivery.claiming(now).failed(now, "网络失败");
        assertEquals("failed", firstFailure.status());
        assertEquals(now.plusMinutes(1), firstFailure.nextRetryAt());

        ReminderDelivery secondFailure = firstFailure.claiming(now).failed(now, "仍然失败");
        assertEquals(now.plusMinutes(5), secondFailure.nextRetryAt());

        ReminderDelivery sent = secondFailure.claiming(now).sent(now);
        assertEquals("sent", sent.status());
        assertNull(sent.lockedUntil());
        assertNull(sent.nextRetryAt());
    }
}
