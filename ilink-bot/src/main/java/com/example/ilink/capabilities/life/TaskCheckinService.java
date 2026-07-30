package com.example.ilink.capabilities.life;

import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import com.example.ilink.capabilities.planning.TaskPlanningService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** 处理计划任务的完成、部分完成、延期和卡住反馈。 */
public final class TaskCheckinService {

    private final PlanSessionStore planSessions;
    private final TaskPlanningService planningService;
    private final PlanReminderService reminders;
    private final LifeStateStore lifeStates;

    public TaskCheckinService(PlanSessionStore planSessions, TaskPlanningService planningService,
                              PlanReminderService reminders, LifeStateStore lifeStates) {
        this.planSessions = planSessions;
        this.planningService = planningService;
        this.reminders = reminders;
        this.lifeStates = lifeStates;
    }

    public CheckinResult checkIn(String userId, String text) {
        TaskPlan plan = planSessions.get(userId);
        if (plan == null) return CheckinResult.failure("当前没有可更新的计划。");
        PlanTask task = findTask(plan, text);
        if (task == null) {
            List<PlanTask> candidates = candidates(plan, text);
            if (candidates.size() > 1) {
                String names = candidates.stream().limit(5).map(item -> "- " + item.title())
                        .collect(java.util.stream.Collectors.joining("\n"));
                return CheckinResult.failure("有多个可能的任务，请带上任务名称：\n" + names);
            }
            return CheckinResult.failure("没有找到待更新的任务，请带上任务名称再试一次。");
        }

        String type = feedbackType(text);
        if (type.isBlank()) return CheckinResult.failure("请告诉我是“完成了”“部分完成”“延期”还是“不会”。");
        TaskPlan updated;
        String message;
        if ("completed".equals(type)) {
            updated = plan.withTasks(plan.tasks().stream()
                    .map(item -> item.id().equals(task.id()) ? item.withStatus("completed") : item).toList());
            reminders.complete(task);
            message = "已完成：" + task.title();
        } else {
            try {
                if ("blocked".equals(type)) {
                    PlanTask review = new PlanTask(plan.id() + "-REVIEW-"
                            + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                            "复习前置概念：" + task.title(),
                            "先定位卡点涉及的定义、公式和基础例题，完成 2 道基础题后再继续原任务。",
                            30, "high", task.scheduledDate(), "pending");
                    List<PlanTask> withReview = new java.util.ArrayList<>();
                    for (PlanTask item : plan.tasks()) {
                        if (item.id().equals(task.id())) withReview.add(review);
                        withReview.add(item);
                    }
                    updated = planningService.replanFrom(plan.withTasks(withReview), review.id(),
                            LocalDate.now().plusDays(1));
                } else {
                    updated = planningService.replanFrom(plan, task.id(), LocalDate.now().plusDays(1));
                }
            } catch (IllegalArgumentException error) {
                return CheckinResult.failure(error.getMessage());
            }
            LocalTime reminderTime = reminderTime(userId, plan.id());
            reminders.sync(userId, updated, reminderTime);
            message = switch (type) {
                case "partial" -> "已记录部分完成，并从明天起重排受影响任务。";
                case "blocked" -> "已记录学习卡点，下一次会先安排前置概念复习，并重排后续任务。";
                default -> "已记录延期，并从明天起重排受影响任务。";
            };
        }
        planSessions.set(userId, updated);
        lifeStates.addActivity(userId, new TaskActivity(UUID.randomUUID().toString(), plan.id(), task.id(),
                type, text, LocalDateTime.now().toString()));
        return new CheckinResult(true, message, plan, updated, task);
    }

    public boolean completeById(String userId, String taskId) {
        TaskPlan plan = planSessions.list(userId).stream()
                .filter(item -> item.tasks().stream().anyMatch(task -> task.id().equals(taskId)))
                .findFirst().orElse(null);
        if (plan == null) return false;
        planSessions.select(userId, plan.id());
        return checkIn(userId, "完成了 " + taskId).success();
    }

    private PlanTask findTask(TaskPlan plan, String text) {
        List<PlanTask> values = candidates(plan, text);
        return values.size() == 1 ? values.getFirst() : null;
    }

    private List<PlanTask> candidates(TaskPlan plan, String text) {
        List<PlanTask> pending = plan.tasks().stream()
                .filter(task -> !"completed".equals(task.status())).toList();
        List<PlanTask> explicit = pending.stream()
                .filter(task -> text.contains(task.id()) || text.contains(task.title()))
                .toList();
        if (!explicit.isEmpty()) return explicit;
        String today = LocalDate.now().toString();
        List<PlanTask> todayTasks = pending.stream()
                .filter(task -> today.equals(task.scheduledDate())).toList();
        return todayTasks.isEmpty() ? pending : todayTasks;
    }

    private String feedbackType(String text) {
        if (text.matches(".*(部分完成|做了一部分|学了一部分).*")) return "partial";
        if (text.matches(".*(不会|没看懂|不理解|卡住).*")) return "blocked";
        if (text.matches(".*(延期|推迟|没做完|未完成|没完成|来不及).*")) return "delayed";
        if (text.matches(".*(完成了|已完成|做完了|学完了|完成).*")) return "completed";
        return "";
    }

    private LocalTime reminderTime(String userId, String planId) {
        StudyPlanProfile profile = lifeStates.profile(userId, planId);
        try {
            return profile == null ? LocalTime.of(20, 0) : LocalTime.parse(profile.reminderTime());
        } catch (RuntimeException error) {
            return LocalTime.of(20, 0);
        }
    }

    public record CheckinResult(boolean success, String message, TaskPlan previous,
                                TaskPlan updated, PlanTask task) {
        static CheckinResult failure(String message) {
            return new CheckinResult(false, message, null, null, null);
        }
    }
}
