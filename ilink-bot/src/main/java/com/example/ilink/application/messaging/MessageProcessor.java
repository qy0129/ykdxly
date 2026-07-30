package com.example.ilink.application.messaging;

import com.example.ilink.application.command.CommandHandler;
import com.example.ilink.application.command.CommandRouter;
import com.example.ilink.application.command.CommandType;
import com.example.ilink.application.conversation.AudioHistoryStore;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.inbox.InboxApplicationService;
import com.example.ilink.capabilities.memory.MemoryExtractor;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.audio.AudioService;
import com.example.ilink.capabilities.audio.AudioSource;
import com.example.ilink.capabilities.documents.DocumentRecord;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.capabilities.documents.rag.RagContextService;
import com.example.ilink.capabilities.documents.rag.Retriever;
import com.example.ilink.capabilities.image.GeneratedImage;
import com.example.ilink.capabilities.image.ImageService;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.platform.media.MediaStore;

import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** 处理一条已经适配的入站消息。 */
public final class MessageProcessor {

    private final ChatHistoryStore chatHistory;
    private final UserSessionStore sessions;
    private final AudioHistoryStore audioHistory;
    private final DocumentSessionStore documentSessions;
    private final AudioService audioService;
    private final ImageService imageService;
    private final DocumentService documentService;
    private final MemoryService memoryService;
    private final MediaStore mediaStore;
    private final ReplySender replySender;
    private final CapabilityDispatcher capabilityDispatcher;
    private final CommandRouter commandRouter;
    private final CommandHandler commandHandler;
    private final MemoryExtractor memoryExtractor;
    private final RagContextService ragContextService;
    private final InboxApplicationService inboxService;

    public MessageProcessor(ChatHistoryStore chatHistory, UserSessionStore sessions,
                            AudioHistoryStore audioHistory, DocumentSessionStore documentSessions,
                            AudioService audioService, ImageService imageService,
                            DocumentService documentService, MemoryService memoryService,
                             MediaStore mediaStore, ReplySender replySender,
                             CapabilityDispatcher capabilityDispatcher,
                             CommandRouter commandRouter, CommandHandler commandHandler,
                             MemoryExtractor memoryExtractor, RagContextService ragContextService,
                             InboxApplicationService inboxService) {
        this.chatHistory = chatHistory;
        this.sessions = sessions;
        this.audioHistory = audioHistory;
        this.documentSessions = documentSessions;
        this.audioService = audioService;
        this.imageService = imageService;
        this.documentService = documentService;
        this.memoryService = memoryService;
        this.mediaStore = mediaStore;
        this.replySender = replySender;
        this.capabilityDispatcher = capabilityDispatcher;
        this.commandRouter = commandRouter;
        this.commandHandler = commandHandler;
        this.memoryExtractor = memoryExtractor;
        this.ragContextService = ragContextService;
        this.inboxService = inboxService;
    }

    public void process(AgentContext context, IncomingMessage message) {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        long startedAt = System.nanoTime();
        try {
            chatHistory.setUserSessionId(userId, sessionId(context, userId));
            client.startTyping(userId);
            List<MessagePart> parts = message.parts();
            if (parts.isEmpty()) return;

            MessagePart first = parts.get(0);
            MessagePart.Text textMessage = parts.stream()
                    .filter(MessagePart.Text.class::isInstance)
                    .map(MessagePart.Text.class::cast)
                    .findFirst().orElse(null);
            MessagePart.Image imageMessage = parts.stream()
                    .filter(MessagePart.Image.class::isInstance)
                    .map(MessagePart.Image.class::cast)
                    .findFirst().orElse(null);
            MessagePart.File fileMessage = parts.stream()
                    .filter(MessagePart.File.class::isInstance)
                    .map(MessagePart.File.class::cast)
                    .findFirst().orElse(null);

            if (imageMessage != null) {
                String description = processImage(context, imageMessage, textMessage == null);
                if (textMessage != null) {
                    String enriched = description == null || description.isBlank() ? textMessage.text()
                            : textMessage.text() + "\n\n图片识别内容：\n" + description;
                    processText(context, enriched, message);
                }
                return;
            }

            if (first instanceof MessagePart.Voice voice) {
                processVoice(context, voice);
                return;
            }

            if (fileMessage != null) {
                processFile(context, fileMessage, textMessage == null);
                if (textMessage != null) processText(context, textMessage.text(), message);
                return;
            }

            if (first instanceof MessagePart.Video video) {
                processVideo(context, video);
                return;
            }

            if (textMessage != null) {
                processText(context, textMessage.text(), message);
                return;
            }

            System.out.println("[" + userId + "] [未知消息类型]");
        } catch (Exception error) {
            System.err.println("处理消息异常: " + error.getMessage());
            try {
                replySender.sendReply(client, userId, "网络波动了，请再发一次～");
            } catch (Exception ignored) {
            }
        } finally {
            long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
            if (elapsedMillis >= 1_000) {
                System.out.println("[Performance] user=" + userId + ", message_ms=" + elapsedMillis);
            }
        }
    }

    private String sessionId(AgentContext context, String userId) {
        return context.channel() == ChannelType.WEB
                && context.conversationId() != null
                && !context.conversationId().isBlank()
                ? context.conversationId()
                : sessions.getCurrentSession(userId).sessionId();
    }

    private String processImage(AgentContext context, MessagePart.Image item,
                                boolean replyImmediately) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        byte[] image = item.content();
        if (image == null || image.length == 0) {
            replySender.sendReply(client, userId, "图片下载失败");
            return "";
        }

        GeneratedImage receivedImage = GeneratedImage.from(image, null);
        Path saved = mediaStore.save(userId, "image", image, receivedImage.extension());
        documentSessions.clear(userId);
        sessions.setLastImage(userId, saved.toString(), UserSessionStore.ImageSource.USER);
        sessions.setPendingImage(userId, saved.toString());

        String description = null;
        try {
            description = imageService.vision(
                    "请完整识别图片中的文字、表格、行列、数字、单位和场景信息。"
                            + "第一行先写不超过50字的摘要，之后按原始顺序完整列出识别内容，不要省略。",
                    Base64.getEncoder().encodeToString(image));
        } catch (Exception error) {
            System.err.println("[Image] 自动图片描述失败: " + error.getMessage());
        }

        if (description != null && !description.isBlank()) {
            sessions.setLastImageAnalysis(userId, description);
            chatHistory.addMedia(userId, "图片", saved.toString(), description);
            if (replyImmediately) {
                replySender.sendReply(client, userId, "收到图片。我看了一下："
                        + imageAnalysisPreview(description)
                        + "。你可以让我生成表格、整理成文档、分析内容或修改图片。");
            }
        } else {
            if (replyImmediately) {
                replySender.sendReply(client, userId,
                        "我已经收到这张图片。你想让我做什么：分析内容、解答题目，还是修改图片？");
            }
        }
        return description;
    }

    private void processVoice(AgentContext context, MessagePart.Voice item) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        byte[] voice = item.content();
        if (voice == null || voice.length == 0) {
            replySender.sendReply(client, userId, "语音下载失败，请再发一次");
            return;
        }

        Path saved = audioService.saveOriginal(userId, voice);
        String text = item.transcript();
        if (text == null || text.isBlank() || !Config.AUDIO_ANALYSIS_ONLY_WHEN_REQUESTED) {
            try {
                String modelText = audioService.transcribe(saved);
                if (modelText != null && !modelText.isBlank()) text = modelText;
            } catch (Exception error) {
                System.err.println("[Audio] 语音转写失败，继续使用 SDK 文本: " + error.getMessage());
            }
        }

        audioHistory.add(userId, AudioSource.USER, saved.toString(), text);
        if (text == null || text.isBlank()) {
            replySender.sendReply(client, userId, "没听清你说的话，请再发一次");
            return;
        }
        chatHistory.addMedia(userId, "语音", saved.toString(), text);
        processText(context, text);
    }

    private void processFile(AgentContext context, MessagePart.File item,
                             boolean replyImmediately) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        String fileName = item.fileName();
        byte[] file = item.content();
        if (file == null || file.length == 0) {
            replySender.sendReply(client, userId, "文件下载失败");
            return;
        }

        String extension = DocumentService.extension(fileName);
        if (!Set.of("pdf", "doc", "docx", "txt", "md", "csv", "xlsx", "xls", "pptx")
                .contains(extension)) {
            replySender.sendReply(client, userId,
                    "当前支持解析 PDF、DOC、DOCX、TXT、MD、CSV、XLSX、XLS 和 PPTX 文件");
            return;
        }

        Path saved = mediaStore.save(userId, "file", file, extension);
        try {
            sessions.clearPendingImage(userId);
            sessions.clearPendingDraw(userId);
            DocumentService.ParsedDocument parsed = documentService.parse(saved, fileName);
            documentSessions.set(userId, new DocumentRecord(
                    parsed.fileName(), parsed.extension(), saved.toString(), parsed.text()));
            try {
                Retriever.IndexResult index = ragContextService.indexDocument(
                        userId, parsed.fileName(), parsed.indexText());
                System.out.println(index.indexed()
                        ? "[RAG] 已索引文件：" + fileName + "，片段数=" + index.chunkCount()
                        : "[RAG] 文件内容已存在，跳过重复索引：" + fileName);
            } catch (Exception error) {
                System.err.println("[RAG] 文件索引失败，文件问答继续使用全文模式: " + error.getMessage());
            }
            chatHistory.addMedia(userId, "文件 " + fileName, saved.toString(), "文件已解析，可进行总结或问答");
            if (replyImmediately) {
                replySender.sendReply(client, userId, "已收到并解析文件：" + fileName
                        + "。你可以让我总结文件，或直接提问文件内容。");
            }
        } catch (Exception error) {
            System.err.println("[Document] 解析失败: " + error.getMessage());
            replySender.sendReply(client, userId, "文件已收到，但暂时无法解析其中的文字内容");
        }
    }

    private void processVideo(AgentContext context, MessagePart.Video item) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        byte[] video = item.content();
        if (video == null || video.length == 0) {
            replySender.sendReply(client, userId, "视频下载失败");
            return;
        }
        Path saved = mediaStore.save(userId, "video", video, "mp4");
        chatHistory.addMedia(userId, "视频", saved.toString(), "视频已保存，等待后续解析");
        replySender.sendReply(client, userId, "视频已保存，暂时还没有接入视频内容解析");
    }

    private void processText(AgentContext context, String text) throws Exception {
        processText(context, text, null);
    }

    private void processText(AgentContext context, String text, IncomingMessage message) throws Exception {
        if (text == null || text.isBlank()) return;
        String userId = context.principalId();
        System.out.println("[" + userId + "] " + text);
        if (commandHandler.trySwitchSession(context.replyChannel(), userId, text)) return;
        CommandType commandType = commandRouter.route(text);
        if (commandType != CommandType.NONE) {
            commandHandler.handle(context.replyChannel(), userId, commandType);
            return;
        }
        if (message != null) {
            InboxApplicationService.HandleResult inbox = inboxService.handle(userId, message.messageId(),
                    message.receivedAt(), message.sourceType(), text);
            if (inbox.consumed()) {
                if (!inbox.response().isBlank()) {
                    replySender.sendReply(context.replyChannel(), userId, inbox.response());
                }
                return;
            }
        }
        String sessionId = sessions.getCurrentSession(userId).sessionId();
        chatHistory.setUserSessionId(userId, sessionId);
        chatHistory.addUserMessage(userId, text);
        memoryExtractor.extract(userId, text);
        capabilityDispatcher.dispatch(context, text);
    }

    private static String imageAnalysisPreview(String analysis) {
        String firstLine = analysis.lines().findFirst().orElse(analysis).strip();
        return firstLine.length() > 80 ? firstLine.substring(0, 80) + "..." : firstLine;
    }
}
