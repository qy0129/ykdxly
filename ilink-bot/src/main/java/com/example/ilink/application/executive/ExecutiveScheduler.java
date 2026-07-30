package com.example.ilink.application.executive;

import java.time.LocalDateTime;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** 即使没有新微信消息也会持续唤醒到期任务。 */
public final class ExecutiveScheduler implements AutoCloseable {
    private final ExecutiveEngine engine;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20), runnable -> {
                Thread thread = new Thread(runnable, "executive-task-worker");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
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
            int capacity = workers.getMaximumPoolSize() - workers.getActiveCount()
                    + workers.getQueue().remainingCapacity();
            if (capacity <= 0) return;
            LocalDateTime now = LocalDateTime.now();
            for (ExecutiveTask task : engine.claimDue(now, Math.min(20, capacity))) {
                workers.execute(() -> engine.executeClaimed(task, LocalDateTime.now()));
            }
        } catch (Exception error) {
            System.err.println("[ExecutiveScheduler] 调度失败: " + error.getMessage());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
        workers.shutdownNow();
        engine.close();
    }
}
