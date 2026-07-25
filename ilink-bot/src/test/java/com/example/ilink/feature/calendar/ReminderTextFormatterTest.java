package com.example.ilink.feature.calendar;

import com.example.ilink.model.CalendarEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderTextFormatterTest {

    @Test
    void keepsNavigationLinkInReminder() {
        LocalDateTime time = LocalDateTime.of(2026, 7, 24, 20, 0);
        CalendarEvent event = new CalendarEvent("event", "user", "杭州东站→杭州西湖", "出行",
                time, time.minusMinutes(15), "none", "", 15, "active",
                "", "travel", "导航链接：https://api.map.baidu.com/direction?test=1", time.minusHours(1));

        String text = ReminderTextFormatter.format(event);

        assertTrue(text.contains("杭州东站→杭州西湖"));
        assertTrue(text.contains("导航链接：https://api.map.baidu.com/direction?test=1"));
    }
}
