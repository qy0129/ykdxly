package com.example.ilink.application.conversation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** 将聊天记录数据库写入移出消息处理线程，并保持写入顺序。 */
final class ChatHistoryAsyncWriter implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-history-writer");
        thread.setDaemon(true);
        return thread;
    });

    void submit(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception error) {
                System.err.println("[聊天记录] 后台写入失败: " + error.getMessage());
            }
        });
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException error) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
