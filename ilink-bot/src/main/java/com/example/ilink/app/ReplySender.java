package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.feature.audio.SynthesizedAudio;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.storage.MediaStore;
import com.example.ilink.tools.audio.SpeechTool;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.gson.JsonObject;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
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
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();

    /** 创建回复发送器并注入音频、媒体和语音历史依赖。 */
    public ReplySender(
            AudioService audioService,
            MediaStore mediaStore,
            AudioHistoryStore audioHistory,
            ToolManager toolManager) {
        this.audioService = audioService;
        this.mediaStore = mediaStore;
        this.audioHistory = audioHistory;
        this.toolManager = toolManager;
    }
    /** 按用户当前默认回复模式发送一条回复。 */
    public void sendReply(ILinkClient client, String userId, String text) throws Exception {
        sendReply(client, userId, text, null, "default");
    }

    /** 根据指定的回复模式和音色发送文本、语音或两者。 */
    public void sendReply(ILinkClient client, String userId, String text,
                           String replyMode, String voiceStyle) throws Exception {
        boolean voice = voiceReplyUsers.contains(userId)
                || "voice".equalsIgnoreCase(Config.REPLY_MODE)
                || "both".equalsIgnoreCase(Config.REPLY_MODE)
                || "both".equalsIgnoreCase(replyMode)
                || "voice".equalsIgnoreCase(replyMode);
        boolean both = voiceReplyUsers.contains(userId)
                || "both".equalsIgnoreCase(Config.REPLY_MODE)
                || "both".equalsIgnoreCase(replyMode);

        if (!voice || both) {
            client.sendText(userId, text);
        }
        if (voice) {
            try {
                JsonObject arguments = new JsonObject();
                arguments.addProperty("text", text);
                arguments.addProperty("voice_style", voiceStyle == null ? "default" : voiceStyle);

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
                    System.err.println("[TTS] MP3 发送失败，改用 WAV: " + mp3SendError.getMessage());
                    sendAudio(client, userId, text, audioService.synthesizeWav(text, voiceStyle));
                }
            } catch (Exception e) {
                if (!both) {
                    client.sendText(userId, text);
                }
                System.err.println("[TTS] 语音回复失败: " + e.getMessage());
            }
        }
    }

    /** 发送实际格式的音频，并在发送成功后保存历史记录。 */
    private void sendAudio(ILinkClient client, String userId, String text,
                           SynthesizedAudio audio) throws Exception {
        String fileName = "reply." + audio.format();
        System.out.println("[TTS] 准备发送 " + audio.format().toUpperCase()
                + " 文件，字节数=" + audio.bytes().length);
        client.sendFile(userId, audio.bytes(), fileName, "语音回复");
        try {
            Path savedAudio = mediaStore.save(userId, "audio", audio.bytes(), audio.format());
            audioHistory.add(userId, AudioSource.BOT, savedAudio.toString(), text);
        } catch (Exception e) {
            System.err.println("[TTS] 语音已发送，但保存历史失败: " + e.getMessage());
        }
    }

    /** 保存路由结果中的回复模式，供后续回复使用。 */
    public void applyReplyMode(String userId, String replyMode) {
        if ("both".equals(replyMode) || "voice".equals(replyMode)) {
            voiceReplyUsers.add(userId);
        } else if ("text".equals(replyMode)) {
            voiceReplyUsers.remove(userId);
        }
    }

    /** 判断该用户是否被设置为只接收语音回复。 */
    public boolean isVoiceOnly(String userId) {
        return voiceReplyUsers.contains(userId) || "voice".equalsIgnoreCase(Config.REPLY_MODE);
    }
}
