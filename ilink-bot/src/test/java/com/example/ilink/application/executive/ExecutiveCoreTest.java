package com.example.ilink.application.executive;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class ExecutiveCoreTest {

    @Test
    void validatesStateTransitions() {
        TaskStateMachine machine = new TaskStateMachine();
        ExecutiveTask task = task(TaskStatus.CREATED, ScheduleRule.NONE);

        assertEquals(TaskStatus.PLANNING, machine.transition(task, TaskStatus.PLANNING).status());
        assertThrows(IllegalStateException.class,
                () -> machine.transition(task, TaskStatus.COMPLETED));
    }

    @Test
    void deduplicatesSubmissionsForSameUser() {
        Fixture fixture = fixture(successTool("ok"));
        ExecutiveTaskService.Submission first = fixture.tasks.submit("u1", "goal", "wechat", "m1",
                "same-key", "medium", null, LocalDateTime.now(), ScheduleRule.NONE, specs("test_tool"));
        ExecutiveTaskService.Submission second = fixture.tasks.submit("u1", "goal", "wechat", "m1",
                "same-key", "medium", null, LocalDateTime.now(), ScheduleRule.NONE, specs("test_tool"));

        assertTrue(first.created());
        assertFalse(second.created());
        assertEquals(first.task().id(), second.task().id());
    }

    @Test
    void executesDependentStepsInOrderAndCompletes() {
        List<String> calls = new ArrayList<>();
        Tool tool = recordingTool(calls);
        Fixture fixture = fixture(tool);
        JsonObject firstArgs = new JsonObject();
        firstArgs.addProperty("value", "first");
        JsonObject secondArgs = new JsonObject();
        secondArgs.addProperty("value", "{{step:1}}-second");
        List<ExecutiveStepSpec> steps = List.of(
                new ExecutiveStepSpec("first", "test", "test_tool", firstArgs,
                        List.of(), false, RiskLevel.READ_ONLY, 2, "non_empty"),
                new ExecutiveStepSpec("second", "test", "test_tool", secondArgs,
                        List.of(1), false, RiskLevel.READ_ONLY, 2, "non_empty"));
        ExecutiveTask task = fixture.tasks.submit("u1", "goal", "wechat", "m1", "ordered",
                "medium", null, LocalDateTime.now(), ScheduleRule.NONE, steps).task();

        fixture.engine.runDue(LocalDateTime.now());
        fixture.engine.runDue(LocalDateTime.now().plusSeconds(1));

        assertEquals(List.of("first", "first-second"), calls);
        assertEquals(TaskStatus.COMPLETED, fixture.store.findTask(task.id()).status());
    }

    @Test
    void retriesEmptyResultWithExpectedBackoff() {
        Fixture fixture = fixture(successTool(""));
        LocalDateTime now = LocalDateTime.now();
        ExecutiveTask task = fixture.tasks.submit("u1", "goal", "wechat", "m1", "retry",
                "medium", null, now, ScheduleRule.NONE, specs("test_tool")).task();

        fixture.engine.runDue(now.plusSeconds(1));

        ExecutiveTask retried = fixture.store.findTask(task.id());
        assertEquals(TaskStatus.RETRYING, retried.status());
        assertEquals(1, retried.retryCount());
        assertTrue(retried.nextRunAt().isAfter(now.plusSeconds(50)));
        assertEquals(1, ExecutiveEngine.backoffMinutes(1));
        assertEquals(5, ExecutiveEngine.backoffMinutes(2));
        assertEquals(15, ExecutiveEngine.backoffMinutes(3));
    }

    @Test
    void pausesForApprovalAndContinuesAfterApproval() {
        Fixture fixture = fixture(successTool("approved"));
        JsonObject args = new JsonObject();
        args.addProperty("value", "work");
        ExecutiveStepSpec spec = new ExecutiveStepSpec("sensitive", "test", "test_tool", args,
                List.of(), true, RiskLevel.EXTERNAL_WRITE, 2, "non_empty");
        ExecutiveTask task = fixture.tasks.submit("u1", "goal", "wechat", "m1", "approval",
                "medium", null, LocalDateTime.now(), ScheduleRule.NONE, List.of(spec)).task();

        fixture.engine.runDue(LocalDateTime.now().plusSeconds(1));
        assertEquals(TaskStatus.WAITING_APPROVAL, fixture.store.findTask(task.id()).status());
        ApprovalRequest request = fixture.approvals.forStep(task.id() + "-S1");
        assertNotNull(request);

        assertTrue(fixture.runtime.decide("u1", request.id(), true).contains("已批准"));
        fixture.engine.runDue(LocalDateTime.now().plusSeconds(2));
        assertEquals(TaskStatus.COMPLETED, fixture.store.findTask(task.id()).status());
    }

    @Test
    void resetsRecurringStepsAndQueuesOutbox() {
        Fixture fixture = fixture(successTool("daily result"));
        LocalDateTime now = LocalDateTime.now();
        ExecutiveTask task = fixture.tasks.submit("u1", "daily", "wechat", "m1", "daily",
                "medium", now.plusMinutes(5), now, ScheduleRule.DAILY, specs("test_tool")).task();

        fixture.engine.runDue(now.plusSeconds(1));

        ExecutiveTask recurring = fixture.store.findTask(task.id());
        assertEquals(TaskStatus.READY, recurring.status());
        assertTrue(recurring.nextRunAt().isAfter(now.plusHours(23)));
        assertEquals(recurring.nextRunAt().plusMinutes(5), recurring.deadlineAt());
        assertEquals(StepStatus.PENDING, fixture.store.loadSteps(task.id()).get(0).status());
        List<OutboxMessage> pending = fixture.outbox.pending("u1", 10);
        assertFalse(pending.isEmpty());
        OutboxMessage sent = pending.get(0);
        fixture.outbox.markSent(sent);
        assertTrue(fixture.store.pendingOutbox("u1", LocalDateTime.now(), 10).stream()
                .noneMatch(message -> message.id().equals(sent.id())));
    }

    @Test
    void exposesPendingNotificationsAcrossUsersForDeliveryWorker() {
        Fixture fixture = fixture(successTool("ok"));
        fixture.outbox.enqueue("TASK-1", "u1", "TASK_COMPLETED", "one");
        fixture.outbox.enqueue("TASK-2", "u2", "TASK_FAILED", "two");

        assertEquals(2, fixture.outbox.pending(10).size());
    }

    @Test
    void leaseAllowsOnlyOneConcurrentClaim() throws Exception {
        Fixture fixture = fixture(successTool("ok"));
        fixture.tasks.submit("u1", "goal", "wechat", "m1", "lease",
                "medium", null, LocalDateTime.now(), ScheduleRule.NONE, specs("test_tool"));
        var pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> {
                start.await();
                return fixture.store.claimDue(LocalDateTime.now().plusSeconds(1), "w1",
                        java.time.Duration.ofMinutes(2), 10).size();
            });
            var second = pool.submit(() -> {
                start.await();
                return fixture.store.claimDue(LocalDateTime.now().plusSeconds(1), "w2",
                        java.time.Duration.ofMinutes(2), 10).size();
            });
            start.countDown();
            assertEquals(1, first.get() + second.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private static Fixture fixture(Tool tool) {
        ExecutiveTaskStore store = ExecutiveTaskStore.inMemory();
        ExecutionLogService logs = new ExecutionLogService(store);
        NotificationOutbox outbox = new NotificationOutbox(store);
        ApprovalService approvals = new ApprovalService(store);
        ExecutiveTaskService tasks = new ExecutiveTaskService(store, logs, outbox);
        ToolManager manager = new ToolManager().register(tool);
        ExecutiveEngine engine = new ExecutiveEngine("test-worker", store,
                new ToolCapabilityExecutor(manager), new DefaultResultVerifier(), approvals, logs, outbox);
        ExecutiveScheduler scheduler = new ExecutiveScheduler(engine);
        ExecutiveRuntime runtime = new ExecutiveRuntime(store, tasks, approvals, logs, outbox, scheduler);
        return new Fixture(store, tasks, approvals, outbox, engine, runtime);
    }

    private static List<ExecutiveStepSpec> specs(String toolName) {
        JsonObject args = new JsonObject();
        args.addProperty("value", "ok");
        return List.of(new ExecutiveStepSpec("step", "test", toolName, args,
                List.of(), false, RiskLevel.READ_ONLY, 3, "non_empty"));
    }

    private static Tool successTool(String output) {
        return tool((context, arguments) -> ToolResult.success(output));
    }

    private static Tool recordingTool(List<String> calls) {
        return tool((context, arguments) -> {
            String value = arguments.get("value").getAsString();
            calls.add(value);
            return ToolResult.success(value);
        });
    }

    private static Tool tool(ToolAction action) {
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("test_tool", "Test", "test", new JsonObject(), false);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
                return action.execute(context, arguments);
            }
        };
    }

    private static ExecutiveTask task(TaskStatus status, ScheduleRule rule) {
        LocalDateTime now = LocalDateTime.now();
        return new ExecutiveTask("TASK-X", "u1", "goal", "test", "source", "key",
                status, "medium", null, now, rule, 0, 1, 0, 3,
                "", "", null, now, now);
    }

    @FunctionalInterface
    private interface ToolAction {
        ToolResult execute(ToolContext context, JsonObject arguments) throws Exception;
    }

    private record Fixture(ExecutiveTaskStore store, ExecutiveTaskService tasks,
                           ApprovalService approvals, NotificationOutbox outbox,
                           ExecutiveEngine engine, ExecutiveRuntime runtime) { }
}
