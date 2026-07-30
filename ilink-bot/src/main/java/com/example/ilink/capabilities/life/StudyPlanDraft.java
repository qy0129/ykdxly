package com.example.ilink.capabilities.life;

/** 学习计划创建过程中尚未补充完整的信息。 */
public record StudyPlanDraft(
        String subject,
        int periodDays,
        int dailyMinutes,
        String level,
        String target,
        String reminderTime) {

    public StudyPlanDraft {
        subject = subject == null ? "" : subject.trim();
        level = level == null ? "" : level.trim();
        target = target == null ? "" : target.trim();
        reminderTime = reminderTime == null ? "" : reminderTime.trim();
    }

    public StudyPlanDraft withPeriodDays(int value) {
        return new StudyPlanDraft(subject, value, dailyMinutes, level, target, reminderTime);
    }

    public StudyPlanDraft withDailyMinutes(int value) {
        return new StudyPlanDraft(subject, periodDays, value, level, target, reminderTime);
    }

    public StudyPlanDraft withLevel(String value) {
        return new StudyPlanDraft(subject, periodDays, dailyMinutes, value, target, reminderTime);
    }

    public StudyPlanDraft withTarget(String value) {
        return new StudyPlanDraft(subject, periodDays, dailyMinutes, level, value, reminderTime);
    }

    public StudyPlanDraft withReminderTime(String value) {
        return new StudyPlanDraft(subject, periodDays, dailyMinutes, level, target, value);
    }
}
