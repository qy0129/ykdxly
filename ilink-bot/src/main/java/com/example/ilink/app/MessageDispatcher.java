package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.document.DocumentAiService;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.tools.audio.AudioTranscribeTool;
import com.example.ilink.tools.audio.SpeechTool;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.document.DocumentEditTool;
import com.example.ilink.tools.document.DocumentGenerateTool;
import com.example.ilink.tools.document.DocumentQATool;
import com.example.ilink.tools.image.DrawTool;
import com.example.ilink.tools.image.ImageAnalysisTool;
import com.example.ilink.tools.image.ImageEditTool;
import com.example.ilink.tools.persona.PersonaSwitchTool;
import com.example.ilink.tools.weather.WeatherTool;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.routing.IntentRecognizer;
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
 * 微信消息分发器。
 *
 * <p>将 SDK 收到的文本、图片、文件、语音等消息转换为统一的处理流程，
 * 同时负责下载媒体、保存会话状态，并把文本请求交给
 * {@link UserRequestHandler}。</p>
 */
public final class MessageDispatcher implements AutoCloseable {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ChatHistoryStore chatHistory = new ChatHistoryStore(httpClient);
    private final UserSessionStore sessions = new UserSessionStore();
    private final AudioHistoryStore audioHistory = new AudioHistoryStore();
    private final DocumentSessionStore documentSessions = new DocumentSessionStore();
    private final IntentRecognizer intentRecognizer = new IntentRecognizer(httpClient);
    private final ChatService chatService = new ChatService(httpClient, chatHistory, sessions);
    private final DocumentAiService documentAiService = new DocumentAiService(httpClient, chatHistory);
    private final AudioService audioService = new AudioService(httpClient);
    private final DocumentService documentService = new DocumentService();
    private final ImageService imageService = new ImageService(httpClient);
    private final WeatherService weatherService = new WeatherService(httpClient);
    private final MediaStore mediaStore = new MediaStore();
    private final ToolManager toolManager = new ToolManager()
            .register(new WeatherTool(weatherService))
            .register(new DrawTool(imageService))
            .register(new ImageAnalysisTool(imageService, sessions))
            .register(new ImageEditTool(imageService, sessions))
            .register(new DocumentQATool(documentAiService, documentSessions))
            .register(new DocumentGenerateTool(documentAiService, documentService, documentSessions))
            .register(new DocumentEditTool(documentAiService, documentService, documentSessions))
            .register(new AudioTranscribeTool(audioService, audioHistory))
            .register(new SpeechTool(audioService))
            .register(new PersonaSwitchTool(sessions));
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();
    private final ReplySender replySender = new ReplySender(
            audioService, mediaStore, audioHistory, toolManager);
    private final UserRequestHandler requestHandler = new UserRequestHandler(
            chatHistory, sessions, documentSessions,
            intentRecognizer, chatService, weatherService,
            mediaStore, replySender, toolManager);
    private final ScheduledExecutorService progressScheduler = Executors.newScheduledThreadPool(1);
    /** 接收一条 SDK 消息，并异步提交给内部处理流程。 */
    public void handleMessage(ILinkClient client, WeixinMessage message) {
        String userId = message.getFrom_user_id();
        boolean voiceOnly = voiceReplyUsers.contains(userId)
                || "voice".equalsIgnoreCase(Config.REPLY_MODE);
        ScheduledFuture<?> progressTask = progressScheduler.schedule(() -> {
            try {
                if (!voiceOnly) {
                    client.sendText(userId, "正在回复中，请稍等......");
                }
            } catch (Exception e) {
                System.err.println("发送处理中提示失败: " + e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);

        try {
            handleMessageInternal(client, message);
        } finally {
            progressTask.cancel(false);
        }
    }

    /** 按消息类型处理文本、图片、文件和语音，并更新对应会话状态。 */
    private void handleMessageInternal(ILinkClient client, WeixinMessage message) {
        try {
            String userId = message.getFrom_user_id();
            client.startTyping(userId);
            List<com.github.wechat.ilink.sdk.core.model.MessageItem> items = message.getItem_list();
            if (items == null || items.isEmpty()) return;

            com.github.wechat.ilink.sdk.core.model.MessageItem first = items.get(0);
            if (first.getText_item() != null) {
                String text = first.getText_item().getText();
                System.out.println("[" + userId + "] " + text);
                requestHandler.handle(client, userId, text);
                return;
            }

            if (first.getImage_item() != null) {
                byte[] image = client.downloadImageFromMessageItem(first);
                if (image == null || image.length == 0) {
                    replySender.sendReply(client, userId, "图片下载失败");
                    return;
                }

                Path saved = mediaStore.save(userId, "image", image, "png");
                sessions.setLastImage(userId, saved.toString());
                sessions.setPendingImage(userId, saved.toString());
                replySender.sendReply(client, userId, "我已经收到这张图片。你想让我做什么：分析内容、解答题目，还是修改图片？");
                return;
            }

            if (first.getVoice_item() != null) {
                byte[] voice = client.downloadVoiceFromMessageItem(first);
                if (voice == null || voice.length == 0) {
                    replySender.sendReply(client, userId, "语音下载失败，请再发一次");
                    return;
                }

                Path saved = audioService.saveOriginal(userId, voice);
                String text = first.getVoice_item().getText();
                if (text == null || text.isBlank() || !Config.AUDIO_ANALYSIS_ONLY_WHEN_REQUESTED) {
                    try {
                        String modelText = audioService.transcribe(saved);
                        if (modelText != null && !modelText.isBlank()) text = modelText;
                    } catch (Exception e) {
                        System.err.println("[Audio] 语音转写失败，继续使用 SDK 文本: " + e.getMessage());
                    }
                }

                audioHistory.add(userId, AudioSource.USER, saved.toString(), text);
                if (text == null || text.isBlank()) {
                    replySender.sendReply(client, userId, "没听清你说的话，请再发一次");
                    return;
                }

                chatHistory.addMedia(userId, "语音", saved.toString(), text);
                System.out.println("[" + userId + "] [语音] " + text);
                requestHandler.handle(client, userId, text);
                return;
            }

            if (first.getFile_item() != null) {
                String fileName = first.getFile_item().getFile_name();
                byte[] file = client.downloadFileFromMessageItem(first);
                if (file == null || file.length == 0) {
                    replySender.sendReply(client, userId, "文件下载失败");
                    return;
                }

                String extension = DocumentService.extension(fileName);
                if (!Set.of("pdf", "doc", "docx", "txt", "md", "csv").contains(extension)) {
                    replySender.sendReply(client, userId, "当前支持解析 PDF、DOC、DOCX、TXT、MD 和 CSV 文件");
                    return;
                }

                Path saved = mediaStore.save(userId, "file", file, extension);
                try {
                    DocumentService.ParsedDocument parsed = documentService.parse(saved, fileName);
                    documentSessions.set(userId, new DocumentRecord(
                            parsed.fileName(), parsed.extension(), saved.toString(), parsed.text()));
                    chatHistory.addMedia(userId, "文件 " + fileName, saved.toString(), "文件已解析，可进行总结或问答");
                    replySender.sendReply(client, userId, "已收到并解析文件：" + fileName
                            + "。你可以让我总结文件，或直接提问文件内容。");
                } catch (Exception e) {
                    System.err.println("[Document] 解析失败: " + e.getMessage());
                    replySender.sendReply(client, userId, "文件已收到，但暂时无法解析其中的文字内容");
                }
                return;
            }

            if (first.getVideo_item() != null) {
                byte[] video = client.downloadVideoFromMessageItem(first);
                if (video != null && video.length > 0) {
                    Path saved = mediaStore.save(userId, "video", video, "mp4");
                    chatHistory.addMedia(userId, "视频", saved.toString(), "视频已保存，等待后续解析");
                    replySender.sendReply(client, userId, "视频已保存，暂时还没有接入视频内容解析");
                } else {
                    replySender.sendReply(client, userId, "视频下载失败");
                }
                return;
            }

            System.out.println("[" + userId + "] [未知消息类型]");
        } catch (Exception e) {
            System.err.println("处理消息异常: " + e.getMessage());
            try {
                client.sendText(message.getFrom_user_id(), "网络波动了，请再发一次～");
            } catch (Exception ignored) {}
        }
    }

    /** 关闭进度调度器，释放分发器持有的后台资源。 */
    @Override
    public void close() {
        progressScheduler.shutdownNow();
    }
}
