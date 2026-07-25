package com.example.ilink.feature.calendar;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HolidayServiceTest {

    @Test
    void reportsFixedHolidayAndWeekend() {
        HolidayService service = new HolidayService();
        assertTrue(service.describe(LocalDate.of(2026, 10, 1)).contains("国庆节"));
        assertTrue(service.describe(LocalDate.of(2026, 7, 25)).contains("周末"));
    }
}
