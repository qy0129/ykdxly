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
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();

    /** 创建回复发送器并注入音频、媒体和语音历史依赖。 */
    public ReplySender(AudioService audioService, MediaStore mediaStore, AudioHistoryStore audioHistory) {
        this.audioService = audioService;
        this.mediaStore = mediaStore;
        this.audioHistory = audioHistory;
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
