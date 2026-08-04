package com.changlu.planner.features.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaskRecurrenceTest {
  @Test
  void createsDailyAndEveryOtherDayDates() {
    LocalDate start = LocalDate.of(2026, 8, 3);
    assertEquals(5, TaskRecurrence.dates("daily", start, start.plusDays(4)).size());
    assertEquals(List.of(start, start.plusDays(2), start.plusDays(4)),
        TaskRecurrence.dates("every_other_day", start, start.plusDays(4)));
  }

  @Test
  void weekdaysExcludeWeekend() {
    LocalDate friday = LocalDate.of(2026, 8, 7);
    assertEquals(List.of(friday, LocalDate.of(2026, 8, 10)),
        TaskRecurrence.dates("weekdays", friday, LocalDate.of(2026, 8, 10)));
  }

  @Test
  void onceOnlyCreatesStartDateAndInvalidRangeIsRejected() {
    LocalDate start = LocalDate.of(2026, 8, 4);
    assertEquals(List.of(start), TaskRecurrence.dates("once", start, null));
    assertThrows(IllegalArgumentException.class,
        () -> TaskRecurrence.dates("daily", start, start.minusDays(1)));
  }
}
