package com.example.ilink.application.executive;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 集中约束任务状态迁移，防止业务代码随意改状态。 */
public final class TaskStateMachine {
    private static final Map<TaskStatus, Set<TaskStatus>> TRANSITIONS = transitions();

    public ExecutiveTask transition(ExecutiveTask task, TaskStatus target) {
        if (task.status() == target) return task;
        if (!TRANSITIONS.getOrDefault(task.status(), Set.of()).contains(target)) {
            throw new IllegalStateException("非法任务状态迁移：" + task.status() + " -> " + target);
        }
        return task.withState(target, task.nextRunAt(), task.lastError());
    }

    public boolean allowed(TaskStatus source, TaskStatus target) {
        return source == target || TRANSITIONS.getOrDefault(source, Set.of()).contains(target);
    }

    private static Map<TaskStatus, Set<TaskStatus>> transitions() {
        Map<TaskStatus, Set<TaskStatus>> values = new EnumMap<>(TaskStatus.class);
        values.put(TaskStatus.CREATED, EnumSet.of(TaskStatus.PLANNING, TaskStatus.CANCELLED));
        values.put(TaskStatus.PLANNING, EnumSet.of(TaskStatus.WAITING_USER, TaskStatus.WAITING_APPROVAL,
                TaskStatus.READY, TaskStatus.FAILED, TaskStatus.CANCELLED));
        values.put(TaskStatus.WAITING_USER, EnumSet.of(TaskStatus.READY, TaskStatus.CANCELLED, TaskStatus.EXPIRED));
        values.put(TaskStatus.WAITING_APPROVAL,
                EnumSet.of(TaskStatus.READY, TaskStatus.CANCELLED, TaskStatus.FAILED, TaskStatus.EXPIRED));
        values.put(TaskStatus.READY, EnumSet.of(TaskStatus.RUNNING, TaskStatus.WAITING_APPROVAL,
                TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED, TaskStatus.EXPIRED));
        values.put(TaskStatus.RUNNING, EnumSet.of(TaskStatus.VERIFYING, TaskStatus.WAITING_USER,
                TaskStatus.WAITING_APPROVAL, TaskStatus.RETRYING, TaskStatus.FAILED, TaskStatus.CANCELLED));
        values.put(TaskStatus.VERIFYING, EnumSet.of(TaskStatus.READY, TaskStatus.COMPLETED,
                TaskStatus.RETRYING, TaskStatus.FAILED));
        values.put(TaskStatus.RETRYING, EnumSet.of(TaskStatus.READY, TaskStatus.FAILED,
                TaskStatus.CANCELLED, TaskStatus.EXPIRED));
        values.put(TaskStatus.FAILED, EnumSet.of(TaskStatus.RETRYING, TaskStatus.CANCELLED));
        return Map.copyOf(values);
    }
}
