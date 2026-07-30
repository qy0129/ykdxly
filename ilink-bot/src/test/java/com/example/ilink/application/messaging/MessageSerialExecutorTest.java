package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSerialExecutorTest {

    @Test
    void preservesOrderForSameUser() throws Exception {
        List<Integer> values = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(2);
        try (MessageSerialExecutor executor = new MessageSerialExecutor()) {
            executor.execute("user-a", () -> {
                values.add(1);
                completed.countDown();
            });
            executor.execute("user-a", () -> {
                values.add(2);
                completed.countDown();
            });
            assertTrue(completed.await(2, TimeUnit.SECONDS));
        }
        assertEquals(List.of(1, 2), values);
    }

    @Test
    void cancelsRunningAndQueuedTasksForKey() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch queuedRan = new CountDownLatch(1);
        try (MessageSerialExecutor executor = new MessageSerialExecutor()) {
            executor.execute("user-a", () -> {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    interrupted.countDown();
                }
            });
            executor.execute("user-a", queuedRan::countDown);
            assertTrue(started.await(2, TimeUnit.SECONDS));

            assertTrue(executor.cancel("user-a"));

            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(1L, queuedRan.getCount());
        }
    }

    @Test
    void resumedTaskWaitsForCancelledExecutionToExit() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseOldTask = new CountDownLatch(1);
        CountDownLatch resumedRan = new CountDownLatch(1);
        try (MessageSerialExecutor executor = new MessageSerialExecutor()) {
            executor.execute("session-a", () -> {
                started.countDown();
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException error) {
                    interrupted.countDown();
                    try {
                        releaseOldTask.await();
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTrue(executor.cancel("session-a"));
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));

            executor.execute("session-a", resumedRan::countDown);
            assertFalse(resumedRan.await(100, TimeUnit.MILLISECONDS));
            releaseOldTask.countDown();
            assertTrue(resumedRan.await(2, TimeUnit.SECONDS));
        }
    }
}
