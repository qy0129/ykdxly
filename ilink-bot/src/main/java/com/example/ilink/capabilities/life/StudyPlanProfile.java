package com.example.ilink.capabilities.life;

import java.util.List;

/** 已创建学习计划的执行配置。日期和时间使用稳定字符串，便于直接持久化。 */
public record StudyPlanProfile(
        String planId,
        String subject,
        String startDate,
        String deadline,
        int dailyMinutes,
        String level,
        String target,
        String reminderTime,
        List<String> sources) {

    public StudyPlanProfile {
        sources = sources == null ? List.of() : List.copyOf(sources);
    }
}
