package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.messaging.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebTaskRegistryTest {

    @Test
    void pausesOnlyTasksForSelectedConversationAndResumesThem() throws Exception {
        WebTaskRegistry registry = new WebTaskRegistry();
        WebTaskRegistry.Task first = registry.create(
                "user-1", "session-1", List.of(new MessagePart.Text("first")));
        WebTaskRegistry.Task other = registry.create(
                "user-1", "session-2", List.of(new MessagePart.Text("other")));
        long firstGeneration = first.start();
        long otherGeneration = other.start();
        assertTrue(firstGeneration > 0L);
        assertTrue(otherGeneration > 0L);
        Thread.sleep(5L);

        assertEquals(List.of(first), registry.pauseSession("user-1", "session-1"));
        assertEquals(WebTaskRegistry.State.PAUSED, first.state());
        assertEquals(WebTaskRegistry.State.RUNNING, other.state());
        assertTrue(first.snapshot().elapsedMs() > 0L);

        assertTrue(first.resume());
        assertEquals(WebTaskRegistry.State.QUEUED, first.state());
        assertFalse(first.resume());
        long resumedGeneration = first.start();
        assertTrue(resumedGeneration > firstGeneration);
        first.fail(firstGeneration, "stale failure");
        assertEquals(WebTaskRegistry.State.RUNNING, first.state());
        first.complete(resumedGeneration);
        assertEquals("completed", first.snapshot().state());
        assertEquals(2, first.snapshot().attempt());
    }

    @Test
    void ownershipPreventsAnotherWebClientFromResumingTask() {
        WebTaskRegistry registry = new WebTaskRegistry();
        WebTaskRegistry.Task task = registry.create(
                "user-1", "session-1", List.of(new MessagePart.Text("private")));

        assertTrue(registry.findOwned(task.id(), "user-1").isPresent());
        assertTrue(registry.findOwned(task.id(), "user-2").isEmpty());
    }
}
