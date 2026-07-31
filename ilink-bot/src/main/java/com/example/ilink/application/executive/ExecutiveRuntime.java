package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Executive Core 对消息入口、控制台和 Automation 暴露的统一门面。 */
public final class ExecutiveRuntime implements AutoCloseable {
    private static final Pattern APPROVE = Pattern.compile("^(批准|同意)\\s*(APR-[A-Z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REJECT = Pattern.compile("^(拒绝|不同意)\\s*(APR-[A-Z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CANCEL = Pattern.compile("^取消任务\\s*(TASK-[A-Z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETRY = Pattern.compile("^重试任务\\s*(TASK-[A-Z0-9]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STATUS = Pattern.compile("^(?:任务状态|查看任务)\\s*(TASK-[A-Z0-9]+)?$", Pattern.CASE_INSENSITIVE);

    private final ExecutiveTaskStore store;
    private final ExecutiveTaskService tasks;
    private final ApprovalService approvals;
    private final ExecutionLogService logs;
    private final NotificationOutbox outbox;
    private final ExecutiveScheduler scheduler;
    private final TaskStateMachine stateMachine = new TaskStateMachine();

    public ExecutiveRuntime(ExecutiveTaskStore store, ExecutiveTaskService tasks,
                            ApprovalService approvals, ExecutionLogService logs,
                            NotificationOutbox outbox, ExecutiveScheduler scheduler) {
        this.store = store;
        this.tasks = tasks;
        this.approvals = approvals;
        this.logs = logs;
        this.outbox = outbox;
        this.scheduler = scheduler;
    }

    public void start() {
        scheduler.start();
    }

    public ExecutiveTaskService.Submission submit(String userId, String goal, String sourceType,
                                                   String sourceId, String dedupKey, String priority,
                                                   LocalDateTime deadlineAt, LocalDateTime nextRunAt,
                                                   ScheduleRule scheduleRule,
                                                   List<ExecutiveStepSpec> steps) {
        return tasks.submit(userId, goal, sourceType, sourceId, dedupKey, priority,
                deadlineAt, nextRunAt, scheduleRule, steps);
    }

    /** 返回 null 表示不是 Executive Core 命令。 */
    public String handleCommand(String userId, String text) {
        String value = text == null ? "" : text.trim();
        Matcher approve = APPROVE.matcher(value);
        if (approve.matches()) return decide(userId, approve.group(2).toUpperCase(), true);
        Matcher reject = REJECT.matcher(value);
        if (reject.matches()) return decide(userId, reject.group(2).toUpperCase(), false);
        Matcher cancel = CANCEL.matcher(value);
        if (cancel.matches()) {
            ExecutiveTask task = tasks.cancel(userId, cancel.group(1).toUpperCase());
            return task == null ? "没有找到这个任务。" : "已取消任务：" + task.id();
        }
        Matcher retry = RETRY.matcher(value);
        if (retry.matches()) {
            ExecutiveTask task = tasks.retry(userId, retry.group(1).toUpperCase());
            return task == null ? "这个任务当前不能重试。" : "已安排重试：" + task.id();
        }
        Matcher status = STATUS.matcher(value);
        if (status.matches()) {
            String taskId = status.group(1);
            return taskId == null || taskId.isBlank() ? listText(userId)
                    : tasks.describe(userId, taskId.toUpperCase());
        }
        return null;
    }

    public String decide(String userId, String approvalId, boolean approved) {
        ApprovalRequest decision = approvals.decide(userId, approvalId, approved);
        if (decision == null) return "没有找到待处理的审批，或者审批已经处理。";
        ExecutiveTask task = store.findTask(decision.taskId());
        ExecutiveStep step = store.loadSteps(decision.taskId()).stream()
                .filter(value -> value.id().equals(decision.stepId())).findFirst().orElse(null);
        if (task == null || step == null) return "审批已记录，但对应任务不存在。";
        if (approved) {
            store.saveStep(step.withStatus(StepStatus.PENDING, step.attempts(), LocalDateTime.now(),
                    step.outputText(), "", step.startedAt(), null));
            ExecutiveTask ready = stateMachine.transition(task, TaskStatus.READY)
                    .withProgress(TaskStatus.READY, task.currentStep(), LocalDateTime.now(),
                            task.retryCount(), "");
            store.saveTask(ready);
            logs.record(ready, step, "APPROVAL_APPROVED", ready.status().name(), approvalId, "{}");
            return "已批准，任务将继续执行：" + task.id();
        }
        store.saveStep(step.withStatus(StepStatus.CANCELLED, step.attempts(), null,
                step.outputText(), "用户拒绝审批", step.startedAt(), LocalDateTime.now()));
        ExecutiveTask cancelled = stateMachine.transition(task, TaskStatus.CANCELLED);
        store.saveTask(cancelled);
        logs.record(cancelled, step, "APPROVAL_REJECTED", cancelled.status().name(), approvalId, "{}");
        return "已拒绝并取消任务：" + task.id();
    }

    public List<ExecutiveTask> listTasks(String userId) {
        return store.listTasks(userId, 100);
    }

    public TaskDetails details(String taskId) {
        ExecutiveTask task = store.findTask(taskId);
        return task == null ? null : new TaskDetails(task, store.loadSteps(taskId), logs.list(taskId));
    }

    public String decideTask(String taskId, boolean approved) {
        ExecutiveTask task = store.findTask(taskId);
        if (task == null) return "没有找到这个任务。";
        ApprovalRequest approval = store.loadSteps(taskId).stream()
                .map(step -> approvals.forStep(step.id()))
                .filter(value -> value != null && value.pending())
                .findFirst().orElse(null);
        return approval == null ? "这个任务当前没有待处理审批。"
                : decide(task.userId(), approval.id(), approved);
    }

    public List<OutboxMessage> pendingNotifications(String userId, int limit) {
        return outbox.pending(userId, limit);
    }

    public List<OutboxMessage> pendingNotifications(int limit) {
        return outbox.pending(limit);
    }

    public void markNotificationSent(OutboxMessage message) {
        outbox.markSent(message);
    }

    public void markNotificationFailed(OutboxMessage message) {
        outbox.markFailed(message);
    }

    private String listText(String userId) {
        List<ExecutiveTask> values = tasks.list(userId);
        if (values.isEmpty()) return "目前没有 Executive Agent 任务。";
        StringBuilder text = new StringBuilder("最近任务：\n");
        values.stream().limit(10).forEach(task -> text.append(task.id()).append(" [")
                .append(task.status()).append("] ").append(task.goal()).append('\n'));
        return text.toString().trim();
    }

    @Override
    public void close() {
        scheduler.close();
    }

    public record TaskDetails(ExecutiveTask task, List<ExecutiveStep> steps,
                              List<ExecutionLog> logs) { }
}
