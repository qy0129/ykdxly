package com.example.ilink.application.messaging;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 保证消息按接收顺序执行。 */
public final class MessageSerialExecutor implements AutoCloseable {

    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

    public synchronized void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        tail = tail.handle((ignored, error) -> null).thenRunAsync(task, workers);
    }

    @Override
    public void close() {
        workers.shutdownNow();
        tail = CompletableFuture.completedFuture(null);
    }
}
