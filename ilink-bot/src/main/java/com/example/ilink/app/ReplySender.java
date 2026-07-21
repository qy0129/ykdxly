package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.storage.MediaStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

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

public final class ReplySender {

    private final AudioService audioService;
    private final MediaStore mediaStore;
    private final AudioHistoryStore audioHistory;
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();

    public ReplySender(AudioService audioService, MediaStore mediaStore, AudioHistoryStore audioHistory) {
        this.audioService = audioService;
        this.mediaStore = mediaStore;
        this.audioHistory = audioHistory;
    }
    public void sendReply(ILinkClient client, String userId, String text) throws Exception {
        sendReply(client, userId, text, null, "default");
    }

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
                byte[] audioBytes = audioService.synthesize(text, voiceStyle);
                Path savedAudio = mediaStore.save(userId, "audio", audioBytes, "mp3");
                audioHistory.add(userId, AudioSource.BOT, savedAudio.toString(), text);
                System.out.println("[TTS] 准备发送 MP3 文件，字节数=" + audioBytes.length);
                client.sendFile(userId, audioBytes, "reply.mp3", "语音回复");
            } catch (Exception e) {
                if (!both) {
                    client.sendText(userId, text);
                }
                System.err.println("[TTS] 语音回复失败: " + e.getMessage());
            }
        }
    }

    public void applyReplyMode(String userId, String replyMode) {
        if ("both".equals(replyMode) || "voice".equals(replyMode)) {
            voiceReplyUsers.add(userId);
        } else if ("text".equals(replyMode)) {
            voiceReplyUsers.remove(userId);
        }
    }

    public boolean isVoiceOnly(String userId) {
        return voiceReplyUsers.contains(userId) || "voice".equalsIgnoreCase(Config.REPLY_MODE);
    }
}
