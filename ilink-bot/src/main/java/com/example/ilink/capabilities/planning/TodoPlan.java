package com.example.ilink.capabilities.planning;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/** 待办规划模型输出经过本地校验后的结果。 */
public record TodoPlan(
        List<TodoDraft> drafts,
        int reminderMinutes,
        boolean supervisionEnabled,
        String supervisionCadence,
        boolean modelGenerated
) {
    public TodoPlan {
        drafts = drafts == null ? List.of() : List.copyOf(drafts);
        if (reminderMinutes < 0 || reminderMinutes > 10080) {
            throw new IllegalArgumentException("待办提醒时间必须在 0 到 10080 分钟之间");
        }
        supervisionCadence = supervisionCadence == null ? "" : supervisionCadence.trim();
    }

    /** 未指定监督时间时沿用现有每日复盘的 21:30 默认值。 */
    public LocalTime supervisionTime() {
        if (!supervisionEnabled) return null;
        return resolveSupervisionTime(supervisionCadence);
    }

    public static LocalTime resolveSupervisionTime(String cadence) {
        LocalDateTime parsed = DateTimeParser.parse(cadence);
        parsed = DateTimeParser.applyPeriodDefault(cadence, parsed);
        return parsed == null ? LocalTime.of(21, 30)
                : parsed.toLocalTime().withSecond(0).withNano(0);
    }

    public boolean weeklySupervisionRequested() {
        return supervisionEnabled && weeklySupervisionRequested(supervisionCadence);
    }

    public static boolean weeklySupervisionRequested(String cadence) {
        return cadence != null && cadence.matches("(?s).*(每\\s*周|每星期|每礼拜).*" );
    }
}
