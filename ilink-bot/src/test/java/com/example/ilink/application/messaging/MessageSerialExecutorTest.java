package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
