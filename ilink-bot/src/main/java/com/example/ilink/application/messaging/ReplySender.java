package com.example.ilink.application.messaging;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.application.conversation.AudioHistoryStore;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.audio.AudioService;
import com.example.ilink.capabilities.audio.SynthesizedAudio;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.capabilities.image.ImageService;
import com.example.ilink.capabilities.persona.Personas;
import com.example.ilink.capabilities.web.TextLinkFormatter;
import com.example.ilink.capabilities.audio.AudioRecord;
import com.example.ilink.capabilities.audio.AudioSource;
import com.example.ilink.capabilities.documents.DocumentRecord;
import com.example.ilink.platform.media.MediaStore;
import com.example.ilink.capabilities.audio.SpeechTool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 统一回复发送器。
 *
 * <p>根据回复模式发送文本或语音，并记录机器人生成的音频，避免业务处理类
 * 直接依赖微信 SDK 的多种发送接口。</p>
 */
public final class ReplySender {

    private final AudioService audioService;
    private final MediaStore mediaStore;
    private final AudioHistoryStore audioHistory;
    private final ToolManager toolManager;
    private final UserSessionStore sessions;
    private final ChatHistoryStore chatHistory;
    /** 仅保存 applyReplyMode 到下一次发送；发送结束后立即消费，不能污染后续消息。 */
    private final ConcurrentHashMap<String, String> pendingReplyModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> defaultReplyModes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastReplyTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> lastTextReplies = new ConcurrentHashMap<>();

    /** 创建回复发送器并注入音频、媒体和语音历史依赖。 */
    public ReplySender(
            AudioService audioService,
            MediaStore mediaStore,
            AudioHistoryStore audioHistory,
            ToolManager toolManager,
            UserSessionStore sessions,
            ChatHistoryStore chatHistory) {
        this.audioService = audioService;
        this.mediaStore = mediaStore;
        this.audioHistory = audioHistory;
        this.toolManager = toolManager;
        this.sessions = sessions;
        this.chatHistory = chatHistory;
    }
    /** 按当前默认回复模式发送一条回复。 */
    public void sendReply(ReplyChannel client, String userId, String text) throws Exception {
        sendReply(client, userId, text, null, "default");
    }

    /** 根据指定的回复模式和音色发送文本、语音或两者。 */
    public void sendReply(ReplyChannel client, String userId, String text,
                           String replyMode, String voiceStyle) throws Exception {
        String displayText = TextLinkFormatter.format(text);
        String oneShotMode = pendingReplyModes.remove(userId);
        String requestedMode = replyMode == null || replyMode.isBlank() || "keep".equalsIgnoreCase(replyMode)
                ? oneShotMode : replyMode;
        boolean explicitMode = requestedMode != null && !requestedMode.isBlank();
        String defaultMode = defaultReplyModes.getOrDefault(userId, Config.REPLY_MODE);
        boolean voice = explicitMode
                ? ("voice".equalsIgnoreCase(requestedMode) || "both".equalsIgnoreCase(requestedMode))
                : "voice".equalsIgnoreCase(defaultMode)
                        || "both".equalsIgnoreCase(defaultMode);
        boolean both = explicitMode
                ? "both".equalsIgnoreCase(requestedMode)
                : "both".equalsIgnoreCase(defaultMode);

        if (!voice || both) {
            client.sendText(userId, displayText);
            markReplySent(userId);
            rememberText(userId, displayText);
        }
        if (voice) {
            try {
                String resolvedVoiceStyle = resolveVoiceStyle(userId, voiceStyle);
                JsonObject arguments = new JsonObject();
                arguments.addProperty("text", text);
                arguments.addProperty("voice_style", resolvedVoiceStyle);

                ToolResult result = toolManager.execute(
                        SpeechTool.NAME,
                        new ToolContext(userId),
                        arguments);
                if (!result.success()) {
                    throw new IllegalStateException(result.output());
                }

                SynthesizedAudio audio = result.dataAs(SynthesizedAudio.class);
                try {
                    sendAudio(client, userId, text, audio);
                } catch (Exception mp3SendError) {
                    if (!audio.isMp3()) {
                        throw mp3SendError;
                    }
                    ConsoleLog.warn("语音合成", "MP3 发送失败，改用 WAV，" + ConsoleLog.errorSummary(mp3SendError));
                    sendAudio(client, userId, text, audioService.synthesizeWav(text, resolvedVoiceStyle));
                }
            } catch (Exception e) {
                if (!both) {
                    client.sendText(userId, displayText);
                    markReplySent(userId);
                    rememberText(userId, displayText);
                }
                ConsoleLog.error("语音合成", "语音回复失败，" + ConsoleLog.errorSummary(e));
            }
        }
    }

    /** 未显式指定音色时，使用当前人格绑定的默认音色。 */
    private String resolveVoiceStyle(String userId, String requestedVoiceStyle) {
        if (requestedVoiceStyle == null || requestedVoiceStyle.isBlank()
                || "default".equalsIgnoreCase(requestedVoiceStyle)) {
            return sessions.getPersonaVoiceStyle(userId);
        }
        return requestedVoiceStyle;
    }

    /** 发送图片，用于快递物流页面二维码。 */
    public void sendImage(ReplyChannel client, String userId, byte[] imageBytes,
                          String fileName, String caption) throws Exception {
        client.sendImage(userId, imageBytes, fileName, caption);
        markReplySent(userId);
    }

    /** 发送实际格式的音频，并在发送成功后保存历史记录。 */
    private void sendAudio(ReplyChannel client, String userId, String text,
                           SynthesizedAudio audio) throws Exception {
        String fileName = "reply." + audio.format();
        ConsoleLog.info("语音合成", "准备发送 " + audio.format().toUpperCase()
                + " 文件，字节数=" + audio.bytes().length);
        client.sendFile(userId, audio.bytes(), fileName, "语音回复");
        markReplySent(userId);
        rememberText(userId, text);
        try {
            Path savedAudio = mediaStore.save(userId, "audio", audio.bytes(), audio.format());
            audioHistory.add(userId, AudioSource.BOT, savedAudio.toString(), text);
        } catch (Exception e) {
            ConsoleLog.warn("语音合成", "语音已发送，但保存历史失败，" + ConsoleLog.errorSummary(e));
        }
    }

    /** 保存路由结果中的回复模式，供后续回复使用。 */
    public void applyReplyMode(String userId, String replyMode) {
        if (userId == null || userId.isBlank()) return;
        if ("both".equals(replyMode) || "voice".equals(replyMode)) {
            pendingReplyModes.put(userId, replyMode);
        } else if ("text".equals(replyMode)) {
            pendingReplyModes.put(userId, "text");
        }
    }

    /** 判断当前会话是否只发送语音回复。 */
    public boolean isVoiceOnly(String userId) {
        return "voice".equalsIgnoreCase(defaultReplyModes.getOrDefault(userId, Config.REPLY_MODE));
    }

    public void setDefaultReplyMode(String userId, String mode) {
        if (userId == null || userId.isBlank()) return;
        if ("text".equals(mode) || "voice".equals(mode) || "both".equals(mode)) {
            defaultReplyModes.put(userId, mode);
        }
    }

    /** 判断当前处理开始后是否已经成功发出回复。 */
    public boolean hasSentReplySince(String userId, long startedAtMillis) {
        return lastReplyTimes.getOrDefault(userId, 0L) >= startedAtMillis;
    }

    private void markReplySent(String userId) {
        markSent(userId);
    }

    /** 供非文字回复链路标记已经开始发送，避免处理中提示插入图片组。 */
    public void markSent(String userId) {
        if (userId != null && !userId.isBlank()) lastReplyTimes.put(userId, System.currentTimeMillis());
    }

    /** 保存最后一条可重发的文字内容。 */
    public void rememberText(String userId, String text) {
        if (userId == null || text == null || text.isBlank()) return;
        lastTextReplies.put(userId, text);
        if (chatHistory != null) chatHistory.addAssistantMessage(userId, text);
    }

    public String lastText(String userId) {
        return userId == null ? "" : lastTextReplies.getOrDefault(userId, "");
    }

}
