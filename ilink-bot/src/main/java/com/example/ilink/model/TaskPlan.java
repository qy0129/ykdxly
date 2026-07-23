package com.example.ilink.model;

import java.util.List;
import java.util.Objects;

/**
 * 用户的一份完整任务计划。
 *
 * @param id 计划唯一标识
 * @param goal 用户最终目标
 * @param deadline 截止日期，格式为 yyyy-MM-dd
 * @param availableTime 用户提供的可用时间说明
 * @param createdDate 计划创建日期
 * @param tasks 计划包含的任务列表
 */
public record TaskPlan(
        String id,
        String goal,
        String deadline,
        String availableTime,
        String createdDate,
        List<PlanTask> tasks) {

    /** 校验计划必填字段，并复制任务列表防止外部修改。 */
    public TaskPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(deadline, "deadline");
        availableTime = availableTime == null ? "" : availableTime;
        Objects.requireNonNull(createdDate, "createdDate");
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    /** 返回替换任务列表后的新计划对象。 */
    public TaskPlan withTasks(List<PlanTask> newTasks) {
        return new TaskPlan(id, goal, deadline, availableTime, createdDate, newTasks);
    }

    /** 计算已经完成的任务数量。 */
    public long completedCount() {
        return tasks.stream().filter(task -> "completed".equals(task.status())).count();
    }

    /** 生成适合直接回复用户或写入文档的中文计划文本。 */
    public String toDisplayText() {
        StringBuilder text = new StringBuilder();
        text.append("任务计划：").append(goal).append('\n')
                .append("计划编号：").append(id).append('\n')
                .append("截止日期：").append(deadline).append('\n')
                .append("可用时间：").append(availableTime.isBlank() ? "未特别说明" : availableTime).append('\n')
                .append("当前进度：").append(completedCount()).append('/').append(tasks.size()).append("\n\n");

        for (int index = 0; index < tasks.size(); index++) {
            PlanTask task = tasks.get(index);
            text.append(index + 1).append(". ")
                    .append("completed".equals(task.status()) ? "[已完成] " : "[待完成] ")
                    .append(task.title()).append('\n')
                    .append("   日期：").append(task.scheduledDate())
                    .append("，预计：").append(task.estimatedMinutes()).append("分钟")
                    .append("，优先级：").append(priorityName(task.priority())).append('\n');
            if (!task.description().isBlank()) {
                text.append("   说明：").append(task.description()).append('\n');
            }
        }
        return text.toString().trim();
    }

    /** 将内部英文优先级转换为中文。 */
    private String priorityName(String priority) {
        return switch (priority) {
            case "high" -> "高";
            case "low" -> "低";
            default -> "中";
        };
    }
}
