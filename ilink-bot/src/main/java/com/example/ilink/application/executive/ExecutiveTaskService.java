package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 创建、去重、查询和人工控制通用任务。 */
public final class ExecutiveTaskService {
    private final ExecutiveTaskStore store;
    private final ExecutionLogService logs;
    private final NotificationOutbox outbox;
    private final TaskStateMachine stateMachine = new TaskStateMachine();

    public ExecutiveTaskService(ExecutiveTaskStore store, ExecutionLogService logs,
                                NotificationOutbox outbox) {
        this.store = store;
        this.logs = logs;
        this.outbox = outbox;
    }

    public Submission submit(String userId, String goal, String sourceType, String sourceId,
                             String dedupKey, String priority, LocalDateTime deadlineAt,
                             LocalDateTime nextRunAt, ScheduleRule scheduleRule,
                             List<ExecutiveStepSpec> specs) {
        ExecutiveTask duplicate = store.findByDedupKey(userId, dedupKey);
        if (duplicate != null) return new Submission(duplicate, false);
        LocalDateTime now = LocalDateTime.now();
        String taskId = "TASK-" + shortId();
        ExecutiveTask created = new ExecutiveTask(taskId, userId, goal, sourceType, sourceId,
                dedupKey, TaskStatus.CREATED, priority, deadlineAt,
                nextRunAt == null ? now : nextRunAt, scheduleRule, 0, 1,
                0, 3, "", "", null, now, now);
        ExecutiveTask planning = stateMachine.transition(created, TaskStatus.PLANNING);
        List<ExecutiveStep> steps = buildSteps(taskId, specs, now);
        ExecutiveTask ready = stateMachine.transition(planning, TaskStatus.READY)
                .withProgress(TaskStatus.READY, 0, created.nextRunAt(), 0, "");
        ExecutiveTask persisted = store.create(ready, steps);
        if (!persisted.id().equals(ready.id())) return new Submission(persisted, false);
        logs.record(ready, null, "TASK_CREATED", ready.status().name(), "任务已创建", "{}");
        return new Submission(ready, true);
    }

    public ExecutiveTask cancel(String userId, String taskId) {
        ExecutiveTask task = owned(userId, taskId);
        if (task == null || task.status().terminal()) return task;
        ExecutiveTask cancelled = stateMachine.transition(task, TaskStatus.CANCELLED);
        store.saveTask(cancelled);
        for (ExecutiveStep step : store.loadSteps(task.id())) {
            if (!step.status().completed()) store.saveStep(step.withStatus(StepStatus.CANCELLED,
                    step.attempts(), null, step.outputText(), "用户取消", step.startedAt(), LocalDateTime.now()));
        }
        logs.record(cancelled, null, "TASK_CANCELLED", cancelled.status().name(), "用户取消任务", "{}");
        outbox.enqueue(task.id(), task.userId(), "TASK_CANCELLED", "任务已取消：" + task.goal());
        return cancelled;
    }

    public ExecutiveTask retry(String userId, String taskId) {
        ExecutiveTask task = owned(userId, taskId);
        if (task == null || task.status() != TaskStatus.FAILED) return null;
        ExecutiveTask retrying = stateMachine.transition(task, TaskStatus.RETRYING)
                .withProgress(TaskStatus.RETRYING, task.currentStep(), LocalDateTime.now(),
                        task.retryCount(), "");
        for (ExecutiveStep step : store.loadSteps(task.id())) {
            if (step.status() == StepStatus.FAILED || step.status() == StepStatus.RETRYING) {
                store.saveStep(step.withStatus(StepStatus.PENDING, step.attempts(), LocalDateTime.now(),
                        step.outputText(), "", step.startedAt(), null));
                break;
            }
        }
        store.saveTask(retrying);
        logs.record(retrying, null, "TASK_RETRY", retrying.status().name(), "用户要求重试", "{}");
        return retrying;
    }

    public ExecutiveTask find(String userId, String taskId) {
        return owned(userId, taskId);
    }

    public List<ExecutiveTask> list(String userId) {
        return store.listTasks(userId, 100);
    }

    public String describe(String userId, String taskId) {
        ExecutiveTask task = owned(userId, taskId);
        if (task == null) return "没有找到这个任务。";
        List<ExecutiveStep> steps = store.loadSteps(task.id());
        long completed = steps.stream().filter(step -> step.status().completed()).count();
        return "任务：" + task.goal() + "\n编号：" + task.id() + "\n状态：" + task.status()
                + "\n进度：" + completed + "/" + steps.size()
                + (task.lastError().isBlank() ? "" : "\n错误：" + task.lastError());
    }

    private ExecutiveTask owned(String userId, String taskId) {
        ExecutiveTask task = store.findTask(taskId);
        return task != null && task.userId().equals(userId) ? task : null;
    }

    private List<ExecutiveStep> buildSteps(String taskId, List<ExecutiveStepSpec> specs, LocalDateTime now) {
        List<ExecutiveStep> steps = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            ExecutiveStepSpec spec = specs.get(index);
            List<String> dependencies = spec.dependsOn().stream()
                    .filter(value -> value >= 1 && value <= specs.size())
                    .map(value -> taskId + "-S" + value).toList();
            steps.add(new ExecutiveStep(taskId + "-S" + (index + 1), taskId, index + 1,
                    spec.title(), spec.capability(), spec.toolName(), spec.arguments().toString(), "",
                    StepStatus.PENDING, dependencies, spec.requiresApproval(), spec.riskLevel(),
                    0, spec.maxAttempts(), now, spec.verificationRule(), "", null, null));
        }
        return List.copyOf(steps);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    public record Submission(ExecutiveTask task, boolean created) { }
}
