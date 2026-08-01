package com.example.ilink.capabilities.calendar;

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

    @Test
    void reportsArmyDayBeforeWeekendFallback() {
        String description = new HolidayService().describe(LocalDate.of(2026, 8, 1));

        assertTrue(description.contains("八一建军节"));
        assertTrue(description.contains("1927年8月1日南昌起义"));
        assertTrue(description.contains("中国人民解放军建军纪念日"));
    }
}
