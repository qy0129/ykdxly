package com.example.ilink.application.executive;

import com.example.ilink.platform.persistence.ExecutiveDatabase;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Executive Core 的统一仓储，数据库不可用时自动使用内存。 */
public final class ExecutiveTaskStore {
    private final ExecutiveDatabase database;
    private final Map<String, ExecutiveTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, Map<String, ExecutiveStep>> steps = new ConcurrentHashMap<>();
    private final Map<String, List<ExecutionLog>> logs = new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequest> approvals = new ConcurrentHashMap<>();
    private final Map<String, OutboxMessage> outbox = new ConcurrentHashMap<>();

    public ExecutiveTaskStore() {
        this(new ExecutiveDatabase());
    }

    public static ExecutiveTaskStore inMemory() {
        return new ExecutiveTaskStore(ExecutiveDatabase.disabled());
    }

    ExecutiveTaskStore(ExecutiveDatabase database) {
        this.database = database;
    }

    public synchronized ExecutiveTask create(ExecutiveTask task, List<ExecutiveStep> taskSteps) {
        ExecutiveTask existing = findByDedupKey(task.userId(), task.dedupKey());
        if (existing != null) return existing;
        saveTask(task);
        for (ExecutiveStep step : taskSteps) saveStep(step);
        return task;
    }

    public void saveTask(ExecutiveTask task) {
        tasks.put(task.id(), task);
        database.saveTask(task);
    }

    public ExecutiveTask findTask(String taskId) {
        ExecutiveTask cached = tasks.get(taskId);
        if (cached != null) return cached;
        ExecutiveTask stored = database.findTask(taskId);
        if (stored != null) tasks.put(taskId, stored);
        return stored;
    }

    public ExecutiveTask findByDedupKey(String userId, String dedupKey) {
        ExecutiveTask cached = tasks.values().stream()
                .filter(task -> task.userId().equals(userId) && task.dedupKey().equals(dedupKey))
                .findFirst().orElse(null);
        if (cached != null) return cached;
        ExecutiveTask stored = database.findByDedupKey(userId, dedupKey);
        if (stored != null) tasks.put(stored.id(), stored);
        return stored;
    }

    public List<ExecutiveTask> listTasks(String userId, int limit) {
        Map<String, ExecutiveTask> merged = new LinkedHashMap<>();
        for (ExecutiveTask task : database.listTasks(userId, limit)) merged.put(task.id(), task);
        tasks.values().stream().filter(task -> userId == null || userId.isBlank() || task.userId().equals(userId))
                .forEach(task -> merged.put(task.id(), task));
        return merged.values().stream().sorted(Comparator.comparing(ExecutiveTask::updatedAt).reversed())
                .limit(Math.max(1, limit)).toList();
    }

    public void saveStep(ExecutiveStep step) {
        steps.computeIfAbsent(step.taskId(), ignored -> new ConcurrentHashMap<>()).put(step.id(), step);
        database.saveStep(step);
    }

    public List<ExecutiveStep> loadSteps(String taskId) {
        Map<String, ExecutiveStep> values = steps.computeIfAbsent(taskId, ignored -> new ConcurrentHashMap<>());
        if (values.isEmpty()) {
            for (ExecutiveStep step : database.loadSteps(taskId)) values.put(step.id(), step);
        }
        return values.values().stream().sorted(Comparator.comparingInt(ExecutiveStep::sequence)).toList();
    }

    public synchronized List<ExecutiveTask> claimDue(LocalDateTime now, String owner,
                                                      Duration lease, int limit) {
        Map<String, ExecutiveTask> candidates = new LinkedHashMap<>();
        for (ExecutiveTask task : database.dueTasks(now, limit)) candidates.put(task.id(), task);
        tasks.values().stream().filter(task -> due(task, now)).forEach(task -> candidates.put(task.id(), task));
        List<ExecutiveTask> claimed = new ArrayList<>();
        for (ExecutiveTask candidate : candidates.values().stream()
                .sorted(Comparator.comparing(ExecutiveTask::nextRunAt)).toList()) {
            if (claimed.size() >= limit) break;
            ExecutiveTask current = findTask(candidate.id());
            if (!due(current, now)) continue;
            LocalDateTime until = now.plus(lease);
            if (!database.tryClaim(current.id(), owner, now, until)) continue;
            ExecutiveTask locked = current.claimed(owner, until);
            saveTask(locked);
            claimed.add(locked);
        }
        return claimed;
    }

    public void addLog(ExecutionLog log) {
        logs.computeIfAbsent(log.taskId(), ignored -> new ArrayList<>()).add(log);
        database.saveLog(log);
    }

    public List<ExecutionLog> logs(String taskId, int limit) {
        List<ExecutionLog> stored = database.loadLogs(taskId, limit);
        if (!stored.isEmpty()) return stored;
        return logs.getOrDefault(taskId, List.of()).stream()
                .sorted(Comparator.comparing(ExecutionLog::createdAt)).limit(Math.max(1, limit)).toList();
    }

    public void saveApproval(ApprovalRequest approval) {
        approvals.put(approval.id(), approval);
        database.saveApproval(approval);
    }

    public ApprovalRequest findApproval(String approvalId) {
        ApprovalRequest cached = approvals.get(approvalId);
        if (cached != null) return cached;
        ApprovalRequest stored = database.findApproval(approvalId);
        if (stored != null) approvals.put(stored.id(), stored);
        return stored;
    }

    public ApprovalRequest findApprovalByStep(String stepId) {
        ApprovalRequest cached = approvals.values().stream().filter(value -> value.stepId().equals(stepId))
                .max(Comparator.comparing(ApprovalRequest::createdAt)).orElse(null);
        if (cached != null) return cached;
        ApprovalRequest stored = database.findApprovalByStep(stepId);
        if (stored != null) approvals.put(stored.id(), stored);
        return stored;
    }

    public void saveOutbox(OutboxMessage message) {
        outbox.put(message.id(), message);
        database.saveOutbox(message);
    }

    public List<OutboxMessage> pendingOutbox(String userId, LocalDateTime now, int limit) {
        Map<String, OutboxMessage> merged = new LinkedHashMap<>();
        for (OutboxMessage message : database.pendingOutbox(userId, now, limit)) merged.put(message.id(), message);
        outbox.values().stream().filter(message -> message.userId().equals(userId)
                        && "PENDING".equals(message.status()) && !message.availableAt().isAfter(now))
                .forEach(message -> merged.put(message.id(), message));
        return merged.values().stream().sorted(Comparator.comparing(OutboxMessage::createdAt))
                .limit(Math.max(1, limit)).toList();
    }

    private boolean due(ExecutiveTask task, LocalDateTime now) {
        if (task == null || !(task.status() == TaskStatus.READY || task.status() == TaskStatus.RETRYING)) return false;
        if (task.nextRunAt() == null || task.nextRunAt().isAfter(now)) return false;
        return task.lockedUntil() == null || !task.lockedUntil().isAfter(now);
    }
}
