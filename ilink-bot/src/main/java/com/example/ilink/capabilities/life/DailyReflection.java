package com.example.ilink.capabilities.life;

/** 一天的执行复盘快照。 */
public record DailyReflection(
        String date,
        int planned,
        int completed,
        int delayed,
        int overdue,
        int pending,
        String observation,
        String tomorrowAdvice) {

    public String toDisplayText() {
        return "每日复盘（" + date + "）\n"
                + "计划 " + planned + " 项，完成 " + completed + " 项，延期 " + delayed + " 项。\n"
                + "当前未完成 " + pending + " 项，其中逾期 " + overdue + " 项。\n\n"
                + "今天需要注意：" + observation + "\n"
                + "明日建议：" + tomorrowAdvice;
    }
}
