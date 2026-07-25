package com.example.ilink.feature.calendar;

import com.example.ilink.model.CalendarEvent;

/** 统一生成在线提醒和离线补发文本，确保事件备注不会在不同发送路径中丢失。 */
public final class ReminderTextFormatter {

    private ReminderTextFormatter() {
    }

    public static String format(CalendarEvent event) {
        String repeat = "none".equals(event.recurrence()) ? ""
                : "\n这是你设置的" + recurrenceName(event.recurrence()) + "提醒，我会继续替你记着。";
        String notes = event.notes().isBlank() ? "" : "\n" + event.notes();
        return "时间到了，来轻轻提醒你一下：" + event.title() + "。"
                + repeat + notes + "\n愿你接下来的安排顺顺利利。";
    }

    private static String recurrenceName(String recurrence) {
        return switch (recurrence) {
            case "daily" -> "每日";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            case "yearly" -> "每年";
            default -> "周期";
        };
    }
}
