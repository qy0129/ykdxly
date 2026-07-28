package com.example.ilink.capabilities.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.Map;

/** 常用固定节日查询；动态农历和调休信息可由联网服务补充。 */
public final class HolidayService {

    private static final Map<MonthDay, String> FIXED_HOLIDAYS = Map.ofEntries(
            Map.entry(MonthDay.of(1, 1), "元旦"),
            Map.entry(MonthDay.of(2, 14), "情人节"),
            Map.entry(MonthDay.of(3, 8), "妇女节"),
            Map.entry(MonthDay.of(5, 1), "劳动节"),
            Map.entry(MonthDay.of(6, 1), "儿童节"),
            Map.entry(MonthDay.of(10, 1), "国庆节"),
            Map.entry(MonthDay.of(12, 25), "圣诞节"));

    public String describe(LocalDate date) {
        String holiday = FIXED_HOLIDAYS.get(MonthDay.from(date));
        if (holiday != null) return "今天是" + holiday + "。";
        if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return "今天是周末，节奏可以稍微放松一点。";
        }
        return "今天没有常见的固定节日。";
    }
}
