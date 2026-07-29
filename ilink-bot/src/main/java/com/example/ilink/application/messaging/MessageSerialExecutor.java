package com.example.ilink.application.messaging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 同一会话按顺序执行，不同用户并行执行，避免一个慢请求阻塞全体用户。 */
public final class MessageSerialExecutor implements AutoCloseable {

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> tails = new ConcurrentHashMap<>();

    public void execute(String key, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(task, "task");
        tails.compute(key, (ignored, tail) -> {
            CompletableFuture<Void> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            CompletableFuture<Void> next = previous.handle((value, error) -> null).thenRunAsync(task, workers);
            next.whenComplete((value, error) -> tails.remove(key, next));
            return next;
        });
    }

    public void execute(Runnable task) {
        execute("_global", task);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        tails.clear();
    }
}
