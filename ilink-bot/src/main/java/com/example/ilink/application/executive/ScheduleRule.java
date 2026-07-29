package com.example.ilink.application.executive;

import java.time.LocalDateTime;

/** 第一版长期任务支持的简单重复规则。 */
public enum ScheduleRule {
    NONE,
    DAILY,
    WEEKLY;

    public LocalDateTime nextAfter(LocalDateTime value) {
        return switch (this) {
            case DAILY -> value.plusDays(1);
            case WEEKLY -> value.plusWeeks(1);
            case NONE -> null;
        };
    }
}
