package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 即使没有新微信消息也会持续唤醒到期任务。 */
public final class ExecutiveScheduler implements AutoCloseable {
    private final ExecutiveEngine engine;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "executive-task-scheduler");
        thread.setDaemon(true);
        return thread;
    });

    public ExecutiveScheduler(ExecutiveEngine engine) {
        this.engine = engine;
    }

    public void start() {
        executor.scheduleWithFixedDelay(this::tick, 1, 3, TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            engine.runDue(LocalDateTime.now());
        } catch (Exception error) {
            System.err.println("[ExecutiveScheduler] 调度失败: " + error.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
