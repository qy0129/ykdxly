package com.example.ilink.tools.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DateTimeParserTest {

    @Test
    void parsesEmbeddedIsoDate() {
        assertEquals(LocalDate.of(2026, 7, 25),
                DateTimeParser.parse("2026-07-25 杭州天气").toLocalDate());
    }

    @Test
    void parsesRelativeSecondsMinutesAndHours() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 16, 30, 0);
        assertEquals(now.plusSeconds(30), DateTimeParser.parse("30秒后", now));
        assertEquals(now.plusSeconds(30), DateTimeParser.parse("三十秒后", now));
        assertEquals(now.plusSeconds(30), DateTimeParser.parse("30 秒之后提醒我要吃饭了", now));
        assertEquals(now.plusSeconds(30), DateTimeParser.parse("三十秒以后", now));
        assertEquals(now.plusMinutes(30), DateTimeParser.parse("半小时以后", now));
        assertEquals(now.plusMinutes(2), DateTimeParser.parse("过2分钟", now));
        assertEquals(now.plusHours(2), DateTimeParser.parse("两小时后", now));
    }

    @Test
    void parsesChineseClockWithMinutesAndSeconds() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 16, 30, 0);
        assertEquals(LocalDateTime.of(2026, 7, 24, 4, 33, 0),
                DateTimeParser.parse("四点三十三分", now));
        assertEquals(LocalDateTime.of(2026, 7, 23, 16, 33, 20),
                DateTimeParser.parse("下午四点三十三分二十秒", now));
        assertEquals(LocalDateTime.of(2026, 7, 23, 17, 18, 0),
                DateTimeParser.parse("下午 17.18 分", now));
        assertEquals(LocalDateTime.of(2026, 7, 23, 17, 18, 0),
                DateTimeParser.parse("17。18", now));
    }

    @Test
    void parsesCurrentTimeOnAnotherDateWithoutInventingClockForDateOnly() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 23, 16, 30, 25);
        assertEquals(LocalDateTime.of(2026, 7, 24, 16, 30, 25),
                DateTimeParser.parse("明天这个时候", now));
        assertTrue(DateTimeParser.hasTimeEvidence("30秒之后提醒我要吃饭了"));
        assertTrue(DateTimeParser.hasTimeEvidence("下午 17.18 分"));
        assertFalse(DateTimeParser.hasTimeEvidence("明天"));
    }
}
