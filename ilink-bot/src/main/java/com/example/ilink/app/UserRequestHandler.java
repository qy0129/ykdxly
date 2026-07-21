package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.document.DocumentAiService;
import com.example.ilink.feature.document.DocumentEditPlan;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.routing.IntentContext;
import com.example.ilink.routing.IntentRecognizer;
import com.example.ilink.routing.IntentResult;
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

public final class UserRequestHandler {

    private final ChatHistoryStore chatHistory;
    private final UserSessionStore sessions;
    private final AudioHistoryStore audioHistory;
    private final DocumentSessionStore documentSessions;
    private final IntentRecognizer intentRecognizer;
    private final ChatService chatService;
    private final DocumentAiService documentAiService;
    private final AudioService audioService;
    private final DocumentService documentService;
    private final ImageService imageService;
    private final MediaStore mediaStore;
    private final ReplySender replySender;

    public UserRequestHandler(ChatHistoryStore chatHistory, UserSessionStore sessions,
                              AudioHistoryStore audioHistory, DocumentSessionStore documentSessions,
                              IntentRecognizer intentRecognizer, ChatService chatService,
                              DocumentAiService documentAiService, AudioService audioService,
                              DocumentService documentService,
                              ImageService imageService, MediaStore mediaStore, ReplySender replySender) {
        this.chatHistory = chatHistory;
        this.sessions = sessions;
        this.audioHistory = audioHistory;
        this.documentSessions = documentSessions;
        this.intentRecognizer = intentRecognizer;
        this.chatService = chatService;
        this.documentAiService = documentAiService;
        this.audioService = audioService;
        this.documentService = documentService;
        this.imageService = imageService;
        this.mediaStore = mediaStore;
        this.replySender = replySender;
    }
    public void handle(ILinkClient client, String userId, String text) throws Exception {
        IntentContext context = new IntentContext(
                sessions.peekPendingImage(userId) != null,
                sessions.getLastImage(userId) != null,
                sessions.peekPendingDraw(userId) != null,
                documentSessions.get(userId) != null);
        IntentResult route = intentRecognizer.recognize(userId, text, context);
        if (route == null) {
            replySender.sendReply(client, userId, "网络波动了，请再发一次～");
            return;
        }

        System.out.println("[Router] intent=" + route.intent()
                + ", reply_mode=" + route.replyMode()
                + ", voice_style=" + route.voiceStyle());

        switch (route.intent()) {
            case "draw" -> handleDraw(client, userId, text, route);
            case "draw_size" -> handleDrawSize(client, userId, route);
            case "persona_switch" -> handlePersonaSwitch(client, userId, text, route);
            case "audio_transcribe" -> handleAudioTranscribe(client, userId, route);
            case "image_action" -> handleImageAction(client, userId, route);
            case "document_summary", "document_question", "generate_file", "document_edit" ->
                    handleDocumentAction(client, userId, text, route);
            default -> {
                String reply = chatService.chat(userId, text);
                if (reply == null || reply.isBlank()) {
                    reply = "网络波动了，请再发一次～";
                }
                chatHistory.add(userId, text, reply);
                replySender.applyReplyMode(userId, route.replyMode());
                System.out.println("[Bot] → " + reply);
                replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            }
        }
    }

    private void handleDraw(ILinkClient client, String userId, String userText,
                            IntentResult route) throws Exception {
        sessions.clearPendingDraw(userId);
        chatHistory.add(userId, userText, "[图片] " + route.cnDescription());
        replySender.applyReplyMode(userId, route.replyMode());

        if ("none".equals(route.imageSize())) {
            sessions.setPendingDraw(userId, route.enPrompt());
            replySender.sendReply(client, userId, "请问你想要什么尺寸？方形(1:1)、竖屏(3:4)还是横屏(16:9)？");
            return;
        }

        System.out.println("[Bot] 绘图prompt: " + route.enPrompt() + " 尺寸: " + route.imageSize());
        byte[] image = imageService.generateImage(route.enPrompt(), route.imageSize());
        if (image != null) {
            client.sendImage(userId, image, "draw.png", route.cnDescription());
        } else {
            replySender.sendReply(client, userId, "绘图失败，请重试");
        }
    }

    private void handleDrawSize(ILinkClient client, String userId, IntentResult route) throws Exception {
        String prompt = sessions.peekPendingDraw(userId);
        if (prompt == null) {
            replySender.sendReply(client, userId, "当前没有等待确认尺寸的绘图请求");
            return;
        }
        if ("none".equals(route.imageSize())) {
            replySender.sendReply(client, userId, "请回复尺寸：方形、竖屏或横屏");
            return;
        }

        sessions.clearPendingDraw(userId);
        byte[] image = imageService.generateImage(prompt, route.imageSize());
        if (image != null) {
            client.sendImage(userId, image, "draw.png", "已按你的要求生成");
        } else {
            replySender.sendReply(client, userId, "绘图失败，请重试");
        }
    }

    private void handlePersonaSwitch(ILinkClient client, String userId, String userText,
                                     IntentResult route) throws Exception {
        String persona = route.persona();
        if (Personas.get(persona) == null) {
            replySender.sendReply(client, userId, "目前可切换的人设有：" + String.join("、", Personas.getAll().keySet()));
            return;
        }

        sessions.setPersona(userId, persona);
        String reply = "好的，已切换为" + persona + "风格。";
        chatHistory.add(userId, userText, reply);
        replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
    }

    private void handleAudioTranscribe(ILinkClient client, String userId,
                                       IntentResult route) throws Exception {
        int index = Math.max(1, Math.min(route.audioIndex(), 100));
        AudioSource source = switch (route.audioSource()) {
            case "bot" -> AudioSource.BOT;
            case "user" -> AudioSource.USER;
            default -> AudioSource.ANY;
        };
        AudioRecord record = audioHistory.find(userId, source, index);
        if (record == null) {
            client.sendText(userId, "没有找到你指定的那条语音");
            return;
        }

        String transcript = record.transcript();
        if (transcript == null || transcript.isBlank()) {
            if (!Files.exists(Path.of(record.path()))) {
                client.sendText(userId, "这条语音文件已不存在，无法转成文字");
                return;
            }
            try {
                transcript = audioService.transcribe(Path.of(record.path()));
                record.setTranscript(transcript);
            } catch (Exception e) {
                System.err.println("[Audio] 历史语音转文字失败: " + e.getMessage());
                client.sendText(userId, "这条语音转文字失败，请稍后重试");
                return;
            }
        }

        String owner = record.source() == AudioSource.BOT ? "我" : "你";
        client.sendText(userId, "第" + index + "条" + owner + "的语音文字：\n" + transcript);
    }

    private void handleImageAction(ILinkClient client, String userId,
                                   IntentResult route) throws Exception {
        String pendingImage = sessions.peekPendingImage(userId);
        String imagePath = pendingImage != null ? pendingImage : sessions.getLastImage(userId);
        if (imagePath == null || !Files.exists(Path.of(imagePath))) {
            replySender.sendReply(client, userId, "没有找到需要处理的图片");
            return;
        }

        if ("clarify".equals(route.imageAction()) || "none".equals(route.imageAction())) {
            replySender.sendReply(client, userId, "请告诉我想怎么处理图片：分析内容、解答题目，还是修改图片？");
            return;
        }

        sessions.clearPendingImage(userId);
        if ("analyze".equals(route.imageAction()) || "solve".equals(route.imageAction())) {
            String visionPrompt = "solve".equals(route.imageAction())
                    ? "请识别图片中的题目，并给出详细、准确的解题过程和答案。用户要求：" + route.imagePrompt()
                    : "请根据用户要求分析这张图片：" + route.imagePrompt();
            String reply = imageService.vision(visionPrompt,
                    Base64.getEncoder().encodeToString(Files.readAllBytes(Path.of(imagePath))));
            chatHistory.addMedia(userId, "图片", imagePath, reply);
            replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            return;
        }

        if ("edit".equals(route.imageAction())) {
            byte[] edited = imageService.editImage(Path.of(imagePath), route.imagePrompt());
            if (edited != null) {
                Path saved = mediaStore.save(userId, "image", edited, "png");
                sessions.setLastImage(userId, saved.toString());
                chatHistory.addMedia(userId, "图片", saved.toString(), "已根据用户要求修改图片");
                client.sendImage(userId, edited, "edited.png", "已完成图片修改");
            } else {
                replySender.sendReply(client, userId, "图片修改失败，请稍后重试");
            }
        }
    }

    private void handleDocumentAction(ILinkClient client, String userId, String userText,
                                      IntentResult route) throws Exception {
        DocumentRecord document = documentSessions.get(userId);
        if (document == null && !"generate_file".equals(route.intent())) {
            replySender.sendReply(client, userId, "请先发送 PDF、DOC、DOCX 或 TXT 文件");
            return;
        }

        if ("document_edit".equals(route.intent()) && "docx".equals(document.extension())) {
            DocumentEditPlan plan = documentAiService.planDocxEdits(document.fileName(), document.text(), userText);
            if (plan == null || plan.edits().isEmpty()) {
                replySender.sendReply(client, userId, "没有生成可执行的 DOCX 修改指令，请把修改位置说得更具体一些");
                return;
            }

            DocumentService.DocxEditResult result = documentService.editDocx(Path.of(document.path()), plan.edits());
            Path saved = mediaStore.save(userId, "file", result.document(), "docx");
            chatHistory.addMedia(userId, "Bot修改文件", saved.toString(), "已在原 DOCX 上应用 " + result.appliedEdits() + " 项修改");
            String caption = result.unmatchedTargets().isEmpty()
                    ? "已在原 DOCX 上完成修改"
                    : "已完成 " + result.appliedEdits() + " 项修改；有 " + result.unmatchedTargets().size() + " 项未能定位";
            client.sendFile(userId, result.document(), "modified.docx", caption);
            return;
        }

        String request;
        if ("document_summary".equals(route.intent())) {
            request = "请总结这份文件，提炼核心观点、重要事实和结论，使用清晰的分点结构。";
        } else if ("document_question".equals(route.intent())) {
            request = userText;
        } else if ("document_edit".equals(route.intent())) {
            request = "请按照用户要求修改这份文件，只输出修改后的完整文件内容，不要解释修改过程。用户要求：" + userText;
        } else {
            request = document == null
                    ? "请根据用户要求生成一份适合整理成文件的完整内容，只输出正文。用户要求：" + userText
                    : "请把这份文件的核心内容整理成一份结构清晰、适合阅读的总结，保留重要事实和结论。用户补充要求：" + userText;
        }

        String answer = document == null
                ? documentAiService.generateDocument(userId, request)
                : documentAiService.chatWithDocument(userId, request, document.fileName(), document.text());
        if (answer == null || answer.isBlank()) {
            replySender.sendReply(client, userId, "文件处理失败，请稍后重试");
            return;
        }

        boolean outputFile = "generate_file".equals(route.intent())
                || "document_edit".equals(route.intent());
        if (!outputFile) {
            replySender.applyReplyMode(userId, route.replyMode());
            replySender.sendReply(client, userId, answer, route.replyMode(), route.voiceStyle());
            return;
        }

        String outputType = "pdf".equals(route.outputFileType()) ? "pdf"
                : "docx".equals(route.outputFileType()) ? "docx" : defaultDocumentOutputType(document);
        String title = document == null ? "生成文件" : document.fileName() +
                ("document_edit".equals(route.intent()) ? "修改版" : "总结");
        byte[] output = "pdf".equals(outputType)
                ? documentService.createPdf(title, answer)
                : documentService.createDocx(title, answer);
        Path saved = mediaStore.save(userId, "file", output, outputType);
        chatHistory.addMedia(userId, "Bot生成文件", saved.toString(), "已根据文件内容生成" + outputType.toUpperCase(Locale.ROOT));
        String outputName = "document_edit".equals(route.intent())
                ? "modified." + outputType : "summary." + outputType;
        client.sendFile(userId, output, outputName, "文件已生成");
    }

    private String defaultDocumentOutputType(DocumentRecord document) {
        if (document == null) return "docx";
        return "pdf".equals(document.extension()) ? "pdf" : "docx";
    }


}
