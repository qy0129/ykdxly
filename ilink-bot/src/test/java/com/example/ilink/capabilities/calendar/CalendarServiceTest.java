package com.example.ilink.capabilities.calendar;

import com.example.ilink.capabilities.calendar.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalendarServiceTest {

    @Test
    void dailyEventExpandsIntoEachDayOfTheDashboardRange() {
        LocalDate from = LocalDate.of(2026, 7, 25);
        CalendarEvent event = event(LocalDateTime.of(2026, 7, 25, 18, 0), "daily");

        List<CalendarEvent> occurrences = CalendarService.occurrencesBetween(event, from, from.plusDays(6));

        assertEquals(7, occurrences.size());
        for (int index = 0; index < 7; index++) {
            assertEquals(from.plusDays(index), occurrences.get(index).startAt().toLocalDate());
            assertEquals(18, occurrences.get(index).startAt().getHour());
        }
    }

    @Test
    void singleEventOnlyAppearsOnItsOwnDay() {
        LocalDate from = LocalDate.of(2026, 7, 25);
        CalendarEvent event = event(LocalDateTime.of(2026, 7, 26, 18, 0), "none");

        List<CalendarEvent> occurrences = CalendarService.occurrencesBetween(event, from, from.plusDays(6));

        assertEquals(1, occurrences.size());
        assertEquals(LocalDate.of(2026, 7, 26), occurrences.getFirst().startAt().toLocalDate());
    }

    @Test
    void monthlyAnchorReturnsToOriginalDayAfterShortMonth() {
        LocalDateTime january = LocalDateTime.of(2027, 1, 31, 8, 0);
        LocalDateTime february = CalendarService.advanceOccurrence(january, "monthly", "31");
        LocalDateTime march = CalendarService.advanceOccurrence(february, "monthly", "31");

        assertEquals(LocalDateTime.of(2027, 2, 28, 8, 0), february);
        assertEquals(LocalDateTime.of(2027, 3, 31, 8, 0), march);
    }

    @Test
    void yearlyLeapDayReturnsOnNextLeapYear() {
        LocalDateTime value = LocalDateTime.of(2024, 2, 29, 9, 30);
        value = CalendarService.advanceOccurrence(value, "yearly", "2-29");
        assertEquals(LocalDateTime.of(2025, 2, 28, 9, 30), value);
        value = CalendarService.advanceOccurrence(value, "yearly", "2-29");
        value = CalendarService.advanceOccurrence(value, "yearly", "2-29");
        value = CalendarService.advanceOccurrence(value, "yearly", "2-29");
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 30), value);
    }

    private CalendarEvent event(LocalDateTime startAt, String recurrence) {
        return new CalendarEvent("event", "user", "吃饭", "提醒", startAt,
                startAt.minusMinutes(10), recurrence, "", 10, "active", "", "", "", startAt);
    }
}
