package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** 写入和查询统一执行时间线。 */
public final class ExecutionLogService {
    private final ExecutiveTaskStore store;

    public ExecutionLogService(ExecutiveTaskStore store) {
        this.store = store;
    }

    public void record(ExecutiveTask task, ExecutiveStep step, String eventType,
                       String status, String message, String payloadJson) {
        store.addLog(new ExecutionLog(UUID.randomUUID().toString(), task.id(),
                step == null ? "" : step.id(), task.userId(), eventType, status,
                message, payloadJson, LocalDateTime.now()));
    }

    public List<ExecutionLog> list(String taskId) {
        return store.logs(taskId, 500);
    }
}
