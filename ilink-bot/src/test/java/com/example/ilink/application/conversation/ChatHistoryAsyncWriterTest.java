package com.example.ilink.application.conversation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatHistoryAsyncWriterTest {

    @Test
    void writesQueuedTasksInSubmissionOrderBeforeClosing() {
        List<Integer> writes = new ArrayList<>();

        try (ChatHistoryAsyncWriter writer = new ChatHistoryAsyncWriter()) {
            writer.submit(() -> writes.add(1));
            writer.submit(() -> writes.add(2));
            writer.submit(() -> writes.add(3));
        }

        assertEquals(List.of(1, 2, 3), writes);
    }
}
