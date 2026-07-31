package com.example.ilink.capabilities.life;

import java.util.List;

/** 一天的执行复盘快照。 */
public record DailyReflection(
        String date,
        int planned,
        int completed,
        int delayed,
        int overdue,
        int pending,
        String observation,
        String tomorrowAdvice,
        List<String> completedItems,
        List<String> unfinishedItems,
        List<String> highlights,
        List<String> problems,
        List<String> patterns,
        List<String> suggestions,
        String tomorrowFocus,
        boolean aiGenerated) {

    public DailyReflection {
        date = clean(date);
        observation = clean(observation);
        tomorrowAdvice = clean(tomorrowAdvice);
        completedItems = copy(completedItems);
        unfinishedItems = copy(unfinishedItems);
        highlights = copy(highlights);
        problems = copy(problems);
        patterns = copy(patterns);
        suggestions = copy(suggestions);
        tomorrowFocus = clean(tomorrowFocus);
    }

    public DailyReflection(String date, int planned, int completed, int delayed, int overdue, int pending,
                           String observation, String tomorrowAdvice) {
        this(date, planned, completed, delayed, overdue, pending, observation, tomorrowAdvice,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), "", false);
    }

    public String toDisplayText() {
        StringBuilder text = new StringBuilder("每日复盘（").append(date).append("）\n\n")
                .append("今日计划 ").append(planned).append(" 项，完成 ").append(completed)
                .append(" 项，延期 ").append(delayed).append(" 项，未完成 ").append(pending)
                .append(" 项，逾期 ").append(overdue).append(" 项。");
        appendSection(text, "已完成", completedItems);
        appendSection(text, "仍需处理", unfinishedItems);
        if (!observation.isBlank()) {
            text.append("\n\n").append(aiGenerated ? "综合分析" : "今天需要注意")
                    .append("\n").append(observation);
        }
        appendSection(text, "今天做得好的地方", highlights);
        appendSection(text, "需要注意的问题", problems);
        appendSection(text, "执行规律", patterns);
        appendNumberedSection(text, "明日建议", suggestions);
        String focus = tomorrowFocus.isBlank() ? tomorrowAdvice : tomorrowFocus;
        if (!focus.isBlank()) text.append("\n\n明日重点\n").append(focus);
        return text.toString();
    }

    private static void appendSection(StringBuilder text, String title, List<String> values) {
        if (values.isEmpty()) return;
        text.append("\n\n").append(title);
        values.forEach(value -> text.append("\n- ").append(value));
    }

    private static void appendNumberedSection(StringBuilder text, String title, List<String> values) {
        if (values.isEmpty()) return;
        text.append("\n\n").append(title);
        for (int index = 0; index < values.size(); index++) {
            text.append('\n').append(index + 1).append(". ").append(values.get(index));
        }
    }

    private static List<String> copy(List<String> values) {
        return values == null ? List.of() : values.stream()
                .map(DailyReflection::clean).filter(value -> !value.isBlank()).toList();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
