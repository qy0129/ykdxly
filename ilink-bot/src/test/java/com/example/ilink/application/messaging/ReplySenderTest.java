package com.example.ilink.application.messaging;

import com.example.ilink.application.conversation.AudioHistoryStore;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.audio.SynthesizedAudio;
import com.example.ilink.platform.media.MediaStore;
import com.example.ilink.platform.persistence.DefaultUserSessionStore;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplySenderTest {

    @Test
    void oneShotVoiceModeDoesNotLeakIntoTheNextReply() throws Exception {
        ToolManager tools = new ToolManager().register(new FakeSpeechTool());
        MediaStore media = new MediaStore() {
            @Override
            public Path save(String userId, String type, byte[] bytes, String extension) {
                return Path.of("test-audio." + extension);
            }
        };
        ReplySender sender = new ReplySender(
                null, media, new AudioHistoryStore(), tools,
                new DefaultUserSessionStore(), null);
        RecordingChannel channel = new RecordingChannel();

        sender.applyReplyMode("u1", "voice");
        sender.sendReply(channel, "u1", "第一条");
        sender.sendReply(channel, "u1", "第二条");

        assertEquals(List.of("第二条"), channel.texts);
        assertEquals(1, channel.files.size());
    }

    @Test
    void sendsIdenticalRepliesForSeparateRequests() throws Exception {
        ReplySender sender = new ReplySender(null, new MediaStore(), new AudioHistoryStore(),
                new ToolManager(), new DefaultUserSessionStore(), null);
        RecordingChannel channel = new RecordingChannel();

        sender.sendReply(channel, "u1", "操作已完成");
        sender.sendReply(channel, "u1", "操作已完成");

        assertEquals(List.of("操作已完成", "操作已完成"), channel.texts);
    }

    private static final class FakeSpeechTool implements Tool {
        private final ToolDefinition definition;

        private FakeSpeechTool() {
            JsonObject properties = new JsonObject();
            properties.add("text", ToolDefinition.stringProperty("text"));
            properties.add("voice_style", ToolDefinition.stringProperty("voice"));
            definition = new ToolDefinition(
                    "synthesize_speech", "speech", "speech",
                    ToolDefinition.objectParameters(properties, "text", "voice_style"), true);
        }

        @Override
        public ToolDefinition definition() {
            return definition;
        }

        @Override
        public ToolResult execute(ToolContext context, JsonObject arguments) {
            return ToolResult.success("ok", new SynthesizedAudio(new byte[]{1, 2, 3}, "wav"));
        }
    }

    private static final class RecordingChannel implements ReplyChannel {
        private final List<String> texts = new ArrayList<>();
        private final List<byte[]> files = new ArrayList<>();

        @Override public void startTyping(String recipientId) { }
        @Override public void sendText(String recipientId, String text) { texts.add(text); }
        @Override public void sendImage(String recipientId, byte[] content, String fileName, String caption) { }
        @Override public void sendFile(String recipientId, byte[] content, String fileName, String caption) {
            files.add(content);
        }
    }
}
