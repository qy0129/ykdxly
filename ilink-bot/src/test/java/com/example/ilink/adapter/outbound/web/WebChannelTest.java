package com.example.ilink.adapter.outbound.web;

import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.conversation.ChatHistoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.net.http.HttpClient;
import com.google.gson.JsonArray;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebChannelTest {

    @TempDir
    Path tempDir;

    @Test
    void replaysHistoryAndDeliversFutureEvents() throws Exception {
        WebEventBroker broker = new WebEventBroker();
        long firstId = broker.publish("user-1", event("first")).id();
        long secondId = broker.publish("user-1", event("second")).id();

        try (WebEventBroker.Subscription subscription = broker.subscribe("user-1", firstId)) {
            assertEquals("second", subscription.poll(1, TimeUnit.SECONDS).event().content());
            broker.publish("user-1", event("third"));
            assertEquals("third", subscription.poll(1, TimeUnit.SECONDS).event().content());
            assertFalse(broker.history("other-user", 0).iterator().hasNext());
            assertTrue(secondId < broker.history("user-1", 0).get(2).id());
        }
    }

    @Test
    void clearingStreamClosesAndUnblocksSubscription() throws Exception {
        WebEventBroker broker = new WebEventBroker();
        WebEventBroker.Subscription subscription = broker.subscribe("user-1", 0);

        broker.clear("user-1");

        assertNull(subscription.poll(1, TimeUnit.SECONDS));
        assertTrue(subscription.isClosed());
    }

    @Test
    void restrictsArtifactsToOwnerAndSanitizesName() throws Exception {
        WebArtifactStore store = new WebArtifactStore(tempDir);
        WebArtifactStore.Artifact artifact = store.save("user-1", new byte[]{1, 2}, "..\\secret.pdf", null);

        assertEquals("secret.pdf", artifact.fileName());
        assertEquals(2, artifact.size());
        assertTrue(store.find("user-1", artifact.id()).isPresent());
        assertTrue(store.find("user-2", artifact.id()).isEmpty());
        assertTrue(artifact.path().startsWith(tempDir.toAbsolutePath().normalize()));
    }

    @Test
    void restoresPersistedArtifactMetadataWithoutWeakeningOwnership() throws Exception {
        WebArtifactStore firstStore = new WebArtifactStore(tempDir);
        WebArtifactStore.Artifact saved = firstStore.save(
                "user-1", new byte[]{7, 8, 9}, "preview.png", "image/png");
        WebArtifactStore restartedStore = new WebArtifactStore(tempDir);

        assertTrue(restartedStore.restore("user-1", saved.id(), saved.fileName(),
                saved.contentType(), saved.size()).isPresent());
        assertTrue(restartedStore.find("user-2", saved.id()).isEmpty());
        assertTrue(restartedStore.restore("user-1", "../../secret", "preview.png",
                "image/png", saved.size()).isEmpty());
    }

    @Test
    void publishesArtifactMetadataForFiles() throws Exception {
        WebEventBroker broker = new WebEventBroker();
        WebReplyChannel channel = new WebReplyChannel(broker, new WebArtifactStore(tempDir));

        channel.sendFile("user-1", new byte[]{4, 5, 6}, "report.pdf", "Report");

        WebEventBroker.Envelope envelope = broker.history("user-1", 0).stream().findFirst().orElse(null);
        assertNotNull(envelope);
        assertEquals(AgentEvent.Type.COMPLETED, envelope.event().type());
        assertEquals("Report", envelope.event().content());
        assertEquals("file", envelope.event().metadata().get("kind"));
        assertEquals("report.pdf", envelope.event().metadata().get("fileName"));
        assertEquals("application/pdf", envelope.event().metadata().get("contentType"));
        assertEquals(3L, envelope.event().metadata().get("size"));
        assertTrue(envelope.event().metadata().containsKey("artifactId"));
    }

    @Test
    void publishesReadableTypingStatus() {
        WebEventBroker broker = new WebEventBroker();
        WebReplyChannel channel = new WebReplyChannel(broker, new WebArtifactStore(tempDir));

        channel.startTyping("user-1");

        AgentEvent event = broker.history("user-1", 0).getFirst().event();
        assertEquals(AgentEvent.Type.STATUS, event.type());
        assertEquals("正在处理", event.content());
        assertEquals("working", event.metadata().get("state"));
    }

    @Test
    void publishesArtifactMetadataForImages() throws Exception {
        WebEventBroker broker = new WebEventBroker();
        WebReplyChannel channel = new WebReplyChannel(broker, new WebArtifactStore(tempDir));

        channel.sendImage("user-1", new byte[]{1, 2, 3, 4}, "preview.webp", "");

        AgentEvent event = broker.history("user-1", 0).getFirst().event();
        assertEquals(AgentEvent.Type.COMPLETED, event.type());
        assertEquals("图片已生成", event.content());
        assertEquals("image", event.metadata().get("kind"));
        assertEquals("preview.webp", event.metadata().get("fileName"));
        assertEquals("image/webp", event.metadata().get("contentType"));
        assertEquals(4L, event.metadata().get("size"));
    }

    @Test
    void recordsGeneratedImageAsAssistantHistory() throws Exception {
        String userId = "web-channel-test-" + UUID.randomUUID();
        String sessionId = "session-" + UUID.randomUUID();
        WebEventBroker broker = new WebEventBroker();
        try (ChatHistoryStore history = new ChatHistoryStore(HttpClient.newHttpClient());
             ChatHistoryStore.SessionScope ignored = history.bindSession(userId, sessionId)) {
            WebReplyChannel channel = new WebReplyChannel(
                    broker, new WebArtifactStore(tempDir), history);
            channel.beginRequest(userId, sessionId, "request-1");
            channel.sendImage(userId, new byte[]{1, 2, 3}, "draw.png", "山间日出");
            channel.sendImage(userId, new byte[]{4, 5, 6}, "draw-again.png", "山间日出");
            channel.endRequest();

            JsonArray messages = new JsonArray();
            history.addHistoryMessages(messages, userId);
            assertEquals(2, messages.size());
            assertEquals("assistant", messages.get(0).getAsJsonObject().get("role").getAsString());
            assertEquals("assistant", messages.get(1).getAsJsonObject().get("role").getAsString());
            assertEquals("山间日出", messages.get(0).getAsJsonObject().get("content").getAsString());
            assertFalse(messages.toString().contains(tempDir.toString()));
        }
    }

    @Test
    void suppressesLateRepliesAfterCancellation() {
        WebEventBroker broker = new WebEventBroker();
        WebReplyChannel channel = new WebReplyChannel(broker, new WebArtifactStore(tempDir));

        channel.beginRequest("user-1", "session-1", "request-1");
        channel.cancel("request-1");
        assertThrows(java.util.concurrent.CancellationException.class,
                () -> channel.sendText("user-1", "late reply"));
        channel.endRequest();
        assertTrue(broker.history("user-1", 0).isEmpty());

        channel.beginRequest("user-1", "session-1", "request-2");
        channel.sendText("user-1", "new reply");
        assertEquals("new reply", channel.consumeCompletedText("request-2"));
        assertNull(channel.consumeCompletedText("request-2"));
        channel.endRequest();
        AgentEvent event = broker.history("user-1", 0).getFirst().event();
        assertEquals("new reply", event.content());
        assertEquals("session-1", event.metadata().get("sessionId"));
        assertEquals("request-2", event.metadata().get("requestId"));
    }

    private static AgentEvent event(String content) {
        return new AgentEvent(AgentEvent.Type.STATUS, content, Map.of("state", "working"));
    }
}
