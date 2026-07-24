package com.example.ilink.feature.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalendarTimeResolverTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 23, 16, 30, 0);
    private final CalendarTimeResolver resolver = new CalendarTimeResolver();

    @Test
    void relativeReminderUsesTriggerTimeInsteadOfLeadMinutes() {
        CalendarDraft draft = new CalendarDraft("吃饭", "健康", "none",
                "", "relative", 30, "second", 0);

        var result = resolver.resolve(draft, "30秒之后提醒我要吃饭了", NOW);

        assertTrue(result.resolved());
        assertEquals(NOW.plusSeconds(30), result.eventAt());
        assertEquals(result.eventAt(), result.remindAt());
        assertEquals("second", result.precision());
    }

    @Test
    void absoluteEventSeparatesEventAndReminderTime() {
        CalendarDraft draft = new CalendarDraft("开会", "工作", "none",
                "", "absolute", 0, "", 0);

        var result = resolver.resolve(draft, "明天八点开会，提前十分钟提醒", NOW);

        assertTrue(result.resolved());
        assertEquals(LocalDateTime.of(2026, 7, 24, 8, 0), result.eventAt());
        assertEquals(LocalDateTime.of(2026, 7, 24, 7, 50), result.remindAt());
        assertEquals(600, result.leadTimeSeconds());
    }

    @Test
    void dateWithoutClockRequiresAnotherTurn() {
        CalendarDraft draft = new CalendarDraft("开会", "工作", "none",
                "明天", "absolute", 0, "", 0);

        assertFalse(resolver.resolve(draft, "明天", NOW).resolved());
    }

    @Test
    void monthlyReminderKeepsDayWhenClockArrivesLater() {
        CalendarDraft draft = new CalendarDraft("交房租", "财务", "monthly",
                "每月五号 八点", "auto", 0, "", 0);

        var result = resolver.resolve(draft, draft.timeExpression(), NOW);

        assertTrue(result.resolved());
        assertEquals(LocalDateTime.of(2026, 8, 5, 8, 0), result.eventAt());
    }
}
