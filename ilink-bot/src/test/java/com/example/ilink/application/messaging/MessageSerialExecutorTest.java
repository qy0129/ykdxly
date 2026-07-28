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
    void keepsMessagesInSubmissionOrder() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch finished = new CountDownLatch(3);

        try (MessageSerialExecutor executor = new MessageSerialExecutor()) {
            for (int index = 1; index <= 3; index++) {
                int value = index;
                executor.execute(() -> {
                    order.add(value);
                    finished.countDown();
                });
            }
            assertTrue(finished.await(3, TimeUnit.SECONDS));
        }

        assertEquals(List.of(1, 2, 3), order);
    }
}
