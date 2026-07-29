package com.example.ilink.application.executive;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 观察、执行、验证、更新和重试的 Agent 核心循环。 */
public final class ExecutiveEngine {
    private static final Duration LEASE = Duration.ofMinutes(2);

    private final String workerId;
    private final ExecutiveTaskStore store;
    private final CapabilityExecutor executor;
    private final ResultVerifier verifier;
    private final ApprovalService approvals;
    private final ExecutionLogService logs;
    private final NotificationOutbox outbox;
    private final TaskStateMachine stateMachine = new TaskStateMachine();

    public ExecutiveEngine(String workerId, ExecutiveTaskStore store, CapabilityExecutor executor,
                           ResultVerifier verifier, ApprovalService approvals,
                           ExecutionLogService logs, NotificationOutbox outbox) {
        this.workerId = workerId;
        this.store = store;
        this.executor = executor;
        this.verifier = verifier;
        this.approvals = approvals;
        this.logs = logs;
        this.outbox = outbox;
    }

    public int runDue(LocalDateTime now) {
        List<ExecutiveTask> tasks = store.claimDue(now, workerId, LEASE, 20);
        for (ExecutiveTask task : tasks) {
            try {
                executeOne(task, now);
            } catch (Exception error) {
                failUnexpected(task, error);
            }
        }
        return tasks.size();
    }

    private void executeOne(ExecutiveTask claimed, LocalDateTime now) throws Exception {
        ExecutiveTask task = claimed.status() == TaskStatus.RETRYING
                ? stateMachine.transition(claimed, TaskStatus.READY) : claimed;
        if (task.deadlineAt() != null && task.deadlineAt().isBefore(now)) {
            ExecutiveTask expired = stateMachine.transition(task, TaskStatus.EXPIRED);
            store.saveTask(expired);
            outbox.enqueue(task.id(), task.userId(), "TASK_EXPIRED", "任务已超过截止时间：" + task.goal());
            return;
        }

        List<ExecutiveStep> steps = store.loadSteps(task.id());
        ExecutiveStep step = nextRunnable(steps);
        if (step == null) {
            if (steps.stream().allMatch(value -> value.status().completed())) {
                finishTask(task, steps, now);
            } else {
                failBlocked(task, steps);
            }
            return;
        }

        if (step.approvalRequired()) {
            ApprovalRequest approval = approvals.forStep(step.id());
            if (approval == null || approval.pending()) {
                approval = approvals.ensurePending(task, step);
                ExecutiveStep waiting = step.withStatus(StepStatus.WAITING_APPROVAL, step.attempts(),
                        null, step.outputText(), "", step.startedAt(), null);
                store.saveStep(waiting);
                ExecutiveTask waitingTask = stateMachine.transition(task, TaskStatus.WAITING_APPROVAL);
                store.saveTask(waitingTask);
                logs.record(waitingTask, waiting, "APPROVAL_REQUIRED", waitingTask.status().name(),
                        approval.actionSummary(), "{}");
                outbox.enqueue(task.id(), task.userId(), "APPROVAL_REQUIRED",
                        "任务需要批准：" + approval.actionSummary() + "\n审批编号：" + approval.id()
                                + "\n回复“批准 " + approval.id() + "”或“拒绝 " + approval.id() + "”。");
                return;
            }
            if ("REJECTED".equals(approval.status())) {
                ExecutiveTask cancelled = stateMachine.transition(task, TaskStatus.CANCELLED);
                store.saveTask(cancelled);
                return;
            }
        }

        ExecutiveTask running = stateMachine.transition(task, TaskStatus.RUNNING)
                .withProgress(TaskStatus.RUNNING, step.sequence(), task.nextRunAt(), task.retryCount(), "");
        store.saveTask(running);
        ExecutiveStep active = step.withStatus(StepStatus.RUNNING, step.attempts() + 1,
                null, step.outputText(), "", LocalDateTime.now(), null);
        store.saveStep(active);
        logs.record(running, active, "STEP_STARTED", active.status().name(), active.title(), active.inputJson());

        ExecutionOutcome raw;
        try {
            raw = executor.execute(running, active, store.loadSteps(task.id()));
        } catch (Exception error) {
            raw = ExecutionOutcome.retry(error.getMessage());
        }
        ExecutiveTask verifying = stateMachine.transition(running, TaskStatus.VERIFYING);
        store.saveTask(verifying);
        ExecutionOutcome result = verifier.verify(active, raw);
        applyOutcome(verifying, active, result, now);
    }

    private void applyOutcome(ExecutiveTask task, ExecutiveStep step,
                              ExecutionOutcome result, LocalDateTime now) {
        switch (result.type()) {
            case SUCCESS -> succeed(task, step, result.output(), now);
            case WAITING_USER -> waitForUser(task, step, result.error());
            case RETRYABLE_FAILURE -> retryOrFail(task, step, result.error(), now);
            case PERMANENT_FAILURE -> fail(task, step, result.error());
        }
    }

    private void succeed(ExecutiveTask task, ExecutiveStep step, String output, LocalDateTime now) {
        ExecutiveStep succeeded = step.withStatus(StepStatus.SUCCEEDED, step.attempts(), null,
                output, "", step.startedAt(), LocalDateTime.now());
        store.saveStep(succeeded);
        logs.record(task, succeeded, "STEP_COMPLETED", succeeded.status().name(),
                succeeded.title(), "{\"outputLength\":" + output.length() + "}");
        List<ExecutiveStep> steps = store.loadSteps(task.id());
        if (steps.stream().allMatch(value -> value.id().equals(step.id()) || value.status().completed())) {
            finishTask(task, steps, now);
        } else {
            ExecutiveTask ready = stateMachine.transition(task, TaskStatus.READY)
                    .withProgress(TaskStatus.READY, step.sequence(), now, 0, "");
            store.saveTask(ready);
        }
    }

    private void waitForUser(ExecutiveTask task, ExecutiveStep step, String message) {
        ExecutiveStep waiting = step.withStatus(StepStatus.PENDING, step.attempts(), null,
                step.outputText(), message, step.startedAt(), null);
        store.saveStep(waiting);
        ExecutiveTask waitingTask = new ExecutiveTask(task.id(), task.userId(), task.goal(), task.sourceType(),
                task.sourceId(), task.dedupKey(), TaskStatus.WAITING_USER, task.priority(), task.deadlineAt(),
                null, task.scheduleRule(), task.currentStep(), task.planVersion(), task.retryCount(),
                task.maxRetries(), message, "", null, task.createdAt(), LocalDateTime.now());
        store.saveTask(waitingTask);
        logs.record(waitingTask, waiting, "WAITING_USER", waitingTask.status().name(), message, "{}");
        outbox.enqueue(task.id(), task.userId(), "WAITING_USER", message);
    }

    private void retryOrFail(ExecutiveTask task, ExecutiveStep step, String error, LocalDateTime now) {
        if (step.attempts() >= step.maxAttempts() || task.retryCount() >= task.maxRetries()) {
            fail(task, step, error);
            return;
        }
        LocalDateTime retryAt = now.plusMinutes(backoffMinutes(step.attempts()));
        ExecutiveStep retrying = step.withStatus(StepStatus.RETRYING, step.attempts(), retryAt,
                step.outputText(), error, step.startedAt(), null);
        store.saveStep(retrying);
        ExecutiveTask retryTask = stateMachine.transition(task, TaskStatus.RETRYING)
                .withProgress(TaskStatus.RETRYING, step.sequence(), retryAt,
                        task.retryCount() + 1, error);
        store.saveTask(retryTask);
        logs.record(retryTask, retrying, "STEP_RETRY", retrying.status().name(), error,
                "{\"nextRunAt\":\"" + retryAt + "\"}");
    }

    private void fail(ExecutiveTask task, ExecutiveStep step, String error) {
        ExecutiveStep failed = step.withStatus(StepStatus.FAILED, step.attempts(), null,
                step.outputText(), error, step.startedAt(), LocalDateTime.now());
        store.saveStep(failed);
        ExecutiveTask failedTask = stateMachine.transition(task, TaskStatus.FAILED)
                .withProgress(TaskStatus.FAILED, step.sequence(), null, task.retryCount(), error);
        store.saveTask(failedTask);
        logs.record(failedTask, failed, "TASK_FAILED", failedTask.status().name(), error, "{}");
        outbox.enqueue(task.id(), task.userId(), "TASK_FAILED",
                "任务执行失败：" + task.goal() + "\n原因：" + error + "\n任务编号：" + task.id());
    }

    private void finishTask(ExecutiveTask task, List<ExecutiveStep> steps, LocalDateTime now) {
        String finalOutput = steps.stream().filter(step -> !step.outputText().isBlank())
                .reduce((first, second) -> second).map(ExecutiveStep::outputText).orElse("任务已完成");
        if (task.scheduleRule() != ScheduleRule.NONE) {
            for (ExecutiveStep step : steps) {
                store.saveStep(step.withStatus(StepStatus.PENDING, 0, null, "", "", null, null));
            }
            LocalDateTime base = task.nextRunAt() == null ? now : task.nextRunAt();
            LocalDateTime next = task.scheduleRule().nextAfter(base);
            ExecutiveTask recurring = stateMachine.transition(task, TaskStatus.READY)
                    .withProgress(TaskStatus.READY, 0, next, 0, "");
            store.saveTask(recurring);
            logs.record(recurring, null, "TASK_CYCLE_COMPLETED", recurring.status().name(),
                    "周期任务本轮完成，下次执行：" + next, "{}");
        } else {
            ExecutiveTask completed = stateMachine.transition(task, TaskStatus.COMPLETED)
                    .withProgress(TaskStatus.COMPLETED, steps.size(), null, 0, "");
            store.saveTask(completed);
            logs.record(completed, null, "TASK_COMPLETED", completed.status().name(), "任务完成", "{}");
        }
        outbox.enqueue(task.id(), task.userId(), "TASK_COMPLETED",
                "任务已完成：" + task.goal() + "\n\n" + finalOutput);
    }

    private ExecutiveStep nextRunnable(List<ExecutiveStep> steps) {
        Set<String> completed = steps.stream().filter(step -> step.status().completed())
                .map(ExecutiveStep::id).collect(Collectors.toSet());
        return steps.stream().filter(step -> !step.status().completed()
                        && step.status() != StepStatus.CANCELLED && step.status() != StepStatus.FAILED)
                .filter(step -> completed.containsAll(step.dependsOn()))
                .findFirst().orElse(null);
    }

    private void failUnexpected(ExecutiveTask task, Exception error) {
        System.err.println("[ExecutiveEngine] task=" + task.id() + " 执行异常: " + error.getMessage());
        ExecutiveTask failed = task.withProgress(TaskStatus.FAILED, task.currentStep(), null,
                task.retryCount(), error.getMessage());
        store.saveTask(failed);
        logs.record(failed, null, "ENGINE_ERROR", failed.status().name(), error.getMessage(), "{}");
    }

    private void failBlocked(ExecutiveTask task, List<ExecutiveStep> steps) {
        String blocked = steps.stream().filter(step -> !step.status().completed())
                .map(step -> step.title() + " 依赖 " + step.dependsOn())
                .collect(Collectors.joining("；"));
        String error = "任务步骤依赖无法满足：" + blocked;
        ExecutiveTask failed = stateMachine.transition(task, TaskStatus.FAILED)
                .withProgress(TaskStatus.FAILED, task.currentStep(), null, task.retryCount(), error);
        store.saveTask(failed);
        logs.record(failed, null, "PLAN_BLOCKED", failed.status().name(), error, "{}");
        outbox.enqueue(task.id(), task.userId(), "TASK_FAILED",
                "任务执行失败：" + task.goal() + "\n原因：步骤依赖无法满足\n任务编号：" + task.id());
    }

    static int backoffMinutes(int attempts) {
        return attempts <= 1 ? 1 : attempts == 2 ? 5 : 15;
    }
}
