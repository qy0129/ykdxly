package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.routing.IntentContext;
import com.example.ilink.routing.IntentRecognizer;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.storage.MediaStore;
import com.example.ilink.tools.audio.AudioTranscribeTool;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.document.DocumentEditTool;
import com.example.ilink.tools.document.DocumentGenerateTool;
import com.example.ilink.tools.document.DocumentQATool;
import com.example.ilink.tools.document.DocumentToolOutput;
import com.example.ilink.tools.image.DrawTool;
import com.example.ilink.tools.image.ImageAnalysisTool;
import com.example.ilink.tools.image.ImageEditTool;
import com.example.ilink.tools.persona.PersonaSwitchTool;
import com.example.ilink.tools.weather.WeatherTool;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * 用户文本请求处理器。
 *
 * <p>先调用唯一的意图识别入口 {@link IntentRecognizer}，再根据识别结果
 * 调用聊天、绘图、图片、音频或文档功能。该类负责流程协调，具体 API 调用
 * 放在各 feature 服务中。</p>
 */
public final class UserRequestHandler {

    private final ChatHistoryStore chatHistory;
    private final UserSessionStore sessions;
    private final DocumentSessionStore documentSessions;
    private final IntentRecognizer intentRecognizer;
    private final ChatService chatService;
    private final WeatherService weatherService;
    private final MediaStore mediaStore;
    private final ReplySender replySender;
    private final ToolManager toolManager;

    /** 注入所有业务服务，保持本类只负责请求编排。 */
    public UserRequestHandler(ChatHistoryStore chatHistory, UserSessionStore sessions,
                              DocumentSessionStore documentSessions,
                              IntentRecognizer intentRecognizer, ChatService chatService,
                              WeatherService weatherService, MediaStore mediaStore,
                              ReplySender replySender, ToolManager toolManager) {
        this.chatHistory = chatHistory;
        this.sessions = sessions;
        this.documentSessions = documentSessions;
        this.intentRecognizer = intentRecognizer;
        this.chatService = chatService;
        this.weatherService = weatherService;
        this.mediaStore = mediaStore;
        this.replySender = replySender;
        this.toolManager = toolManager;
    }
    /** 识别用户意图并调用对应功能处理器。 */
    public void handle(ILinkClient client, String userId, String text) throws Exception {
        if (sessions.hasPendingWeatherLocations(userId)) {
            handleWeatherLocationSelection(client, userId, text);
            return;
        }

        // 根据当前用户的临时状态构造上下文，让意图识别知道用户正在处理什么。
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

        System.out.println("[意图识别] 意图=" + intentName(route.intent())
                + "，回复方式=" + replyModeName(route.replyMode())
                + "，音色=" + voiceStyleName(route.voiceStyle()));

        switch (route.intent()) {
            case "draw" -> handleDraw(client, userId, text, route);
            case "draw_size" -> handleDrawSize(client, userId, route);
            case "persona_switch" -> handlePersonaSwitch(client, userId, text, route);
            case "audio_transcribe" -> handleAudioTranscribe(client, userId, route);
            case "image_action" -> handleImageAction(client, userId, route);
            case "weather" -> handleWeather(client, userId, text, route);
            case "document_summary", "document_question", "generate_file", "document_edit" ->
                    handleDocumentAction(client, userId, text, route);
            default -> {
                String reply = chatService.chat(userId, text);
                if (reply == null || reply.isBlank()) {
                    reply = "网络波动了，请再发一次～";
                }
                chatHistory.add(userId, text, reply);
                replySender.applyReplyMode(userId, route.replyMode());
                System.out.println("[机器人回复] " + reply);
                replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            }
        }
    }

    /** 处理绘图请求；缺少尺寸时先保存提示词，等待用户补充。 */
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

        JsonObject arguments = new JsonObject();
        arguments.addProperty("prompt", route.enPrompt());
        arguments.addProperty("image_size", route.imageSize());
        ToolResult result = toolManager.execute(
                DrawTool.NAME, new ToolContext(userId), arguments);
        byte[] image = result.dataAs(byte[].class);
        if (result.success() && image != null) {
            client.sendImage(userId, image, "draw.png", route.cnDescription());
        } else {
            replySender.sendReply(client, userId, result.output());
        }
    }

    /** 处理用户对上一次绘图请求补充的尺寸选择。 */
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
        JsonObject arguments = new JsonObject();
        arguments.addProperty("prompt", prompt);
        arguments.addProperty("image_size", route.imageSize());
        ToolResult result = toolManager.execute(
                DrawTool.NAME, new ToolContext(userId), arguments);
        byte[] image = result.dataAs(byte[].class);
        if (result.success() && image != null) {
            client.sendImage(userId, image, "draw.png", "已按你的要求生成");
        } else {
            replySender.sendReply(client, userId, result.output());
        }
    }

    /** 校验并切换用户当前人设。 */
    private void handlePersonaSwitch(ILinkClient client, String userId, String userText,
                                     IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("persona", route.persona());
        ToolResult result = toolManager.execute(
                PersonaSwitchTool.NAME, new ToolContext(userId), arguments);
        if (result.success()) {
            chatHistory.add(userId, userText, result.output());
        }
        replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
    }

    /** 查找历史语音，必要时调用转写服务并返回文字。 */
    private void handleAudioTranscribe(ILinkClient client, String userId,
                                       IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("source", route.audioSource());
        arguments.addProperty("index", route.audioIndex());
        ToolResult result = toolManager.execute(
                AudioTranscribeTool.NAME, new ToolContext(userId), arguments);
        client.sendText(userId, result.output());
    }

    /** 处理图片分析、解题和编辑请求。 */
    private void handleImageAction(ILinkClient client, String userId,
                                   IntentResult route) throws Exception {
        if ("clarify".equals(route.imageAction()) || "none".equals(route.imageAction())) {
            replySender.sendReply(client, userId, "请告诉我想怎么处理图片：分析内容、解答题目，还是修改图片？");
            return;
        }

        if ("analyze".equals(route.imageAction()) || "solve".equals(route.imageAction())) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("request", route.imagePrompt());
            arguments.addProperty("mode", route.imageAction());
            ToolResult result = toolManager.execute(
                    ImageAnalysisTool.NAME, new ToolContext(userId), arguments);
            if (result.success()) {
                String imagePath = result.dataAs(String.class);
                chatHistory.addMedia(userId, "图片", imagePath, result.output());
            }
            replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
            return;
        }

        if ("edit".equals(route.imageAction())) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("prompt", route.imagePrompt());
            ToolResult result = toolManager.execute(
                    ImageEditTool.NAME, new ToolContext(userId), arguments);
            byte[] edited = result.dataAs(byte[].class);
            if (result.success() && edited != null) {
                Path saved = mediaStore.save(userId, "image", edited, "png");
                sessions.setLastImage(userId, saved.toString());
                chatHistory.addMedia(userId, "图片", saved.toString(), "已根据用户要求修改图片");
                client.sendImage(userId, edited, "edited.png", "已完成图片修改");
            } else {
                replySender.sendReply(client, userId, result.output());
            }
        }
    }

    /** 查询天气；同名地点时保存候选项并等待用户选择。 */
    private void handleWeather(ILinkClient client, String userId, String userText,
                               IntentResult route) throws Exception {
        String locationName = route.weatherLocation();
        if (locationName == null || locationName.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我要查询哪个城市、区县或乡镇的天气。",
                    route.replyMode(), route.voiceStyle());
            return;
        }

        JsonObject arguments = new JsonObject();
        arguments.addProperty("location", locationName);
        arguments.addProperty("day", route.weatherDay());
        ToolResult result = toolManager.execute(
                WeatherTool.NAME, new ToolContext(userId), arguments);
        if (!result.success()) {
            replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
            return;
        }
        WeatherTool.WeatherOutput output = result.dataAs(WeatherTool.WeatherOutput.class);
        List<WeatherLocation> locations = output.locations();
        if (locations.size() > 1) {
            sessions.setPendingWeatherLocations(userId, locations, output.day());
            replySender.sendReply(client, userId, buildWeatherLocationChoices(locations),
                    route.replyMode(), route.voiceStyle());
            return;
        }
        chatHistory.add(userId, userText, result.output());
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
    }

    /** 处理用户对同名地点的序号选择或补充地点信息。 */
    private void handleWeatherLocationSelection(ILinkClient client, String userId, String text) throws Exception {
        if ("取消".equals(text.trim())) {
            sessions.clearPendingWeatherLocations(userId);
            replySender.sendReply(client, userId, "已取消天气查询。");
            return;
        }

        List<WeatherLocation> candidates = sessions.getPendingWeatherLocations(userId);
        String weatherDay = sessions.getPendingWeatherDay(userId);
        int choice = parseLocationChoice(text);
        if (choice > 0 && choice <= candidates.size()) {
            sessions.clearPendingWeatherLocations(userId);
            sendWeatherReply(client, userId, text, candidates.get(choice - 1), weatherDay, "keep", "default");
            return;
        }

        List<WeatherLocation> refinedLocations = weatherService.searchLocations(text);
        if (refinedLocations.size() == 1) {
            sessions.clearPendingWeatherLocations(userId);
            sendWeatherReply(client, userId, text, refinedLocations.get(0), weatherDay, "keep", "default");
            return;
        }
        if (refinedLocations.size() > 1) {
            sessions.setPendingWeatherLocations(userId, refinedLocations, weatherDay);
            replySender.sendReply(client, userId, buildWeatherLocationChoices(refinedLocations));
            return;
        }

        replySender.sendReply(client, userId, "请回复地点序号，或补充完整的省、市、县；回复“取消”可结束查询。");
    }

    /** 请求天气服务并发送统一格式的回复。 */
    private void sendWeatherReply(ILinkClient client, String userId, String userText,
                                  WeatherLocation location, String weatherDay,
                                  String replyMode, String voiceStyle) throws Exception {
        int dayOffset = "tomorrow".equals(weatherDay) ? 1 : 0;
        String reply = weatherService.queryWeather(location, dayOffset);
        chatHistory.add(userId, userText, reply);
        replySender.applyReplyMode(userId, replyMode);
        replySender.sendReply(client, userId, reply, replyMode, voiceStyle);
    }

    /** 生成同名地点的可选列表。 */
    private String buildWeatherLocationChoices(List<WeatherLocation> locations) {
        StringBuilder reply = new StringBuilder("找到多个同名地点，请回复序号，或补充省、市、县：\n");
        for (int index = 0; index < locations.size(); index++) {
            reply.append(index + 1).append(". ").append(locations.get(index).displayName()).append('\n');
        }
        return reply.append("回复“取消”可结束查询。").toString();
    }

    /** 将纯数字文本转换为候选地点序号。 */
    private int parseLocationChoice(String text) {
        try {
            return text.trim().matches("\\d+") ? Integer.parseInt(text.trim()) : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 处理文档问答、总结、生成和 DOCX 编辑请求。 */
    private void handleDocumentAction(ILinkClient client, String userId, String userText,
                                      IntentResult route) throws Exception {
        DocumentRecord document = documentSessions.get(userId);
        if (document == null && !"generate_file".equals(route.intent())) {
            replySender.sendReply(client, userId, "请先发送 PDF、DOC、DOCX 或 TXT 文件");
            return;
        }

        if ("document_summary".equals(route.intent()) || "document_question".equals(route.intent())) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("request", userText);
            arguments.addProperty("action",
                    "document_summary".equals(route.intent()) ? "summary" : "question");
            ToolResult result = toolManager.execute(
                    DocumentQATool.NAME, new ToolContext(userId), arguments);
            replySender.applyReplyMode(userId, route.replyMode());
            replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
            return;
        }

        String outputType = "pdf".equals(route.outputFileType()) ? "pdf"
                : "docx".equals(route.outputFileType()) ? "docx" : defaultDocumentOutputType(document);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("request", userText);
        arguments.addProperty("output_type", outputType);
        String toolName = "document_edit".equals(route.intent())
                ? DocumentEditTool.NAME : DocumentGenerateTool.NAME;
        ToolResult result = toolManager.execute(toolName, new ToolContext(userId), arguments);
        if (!result.success()) {
            replySender.sendReply(client, userId, result.output());
            return;
        }

        DocumentToolOutput output = result.dataAs(DocumentToolOutput.class);
        Path saved = mediaStore.save(userId, "file", output.bytes(), output.extension());
        chatHistory.addMedia(userId, "机器人生成文件", saved.toString(), output.caption());
        client.sendFile(userId, output.bytes(), output.fileName(), output.caption());
    }

    /** 根据原文档类型选择默认输出格式。 */
    private String defaultDocumentOutputType(DocumentRecord document) {
        if (document == null) return "docx";
        return "pdf".equals(document.extension()) ? "pdf" : "docx";
    }

    /** 将内部英文意图转换为便于阅读的中文名称。 */
    private String intentName(String intent) {
        return switch (intent) {
            case "chat" -> "聊天";
            case "draw" -> "绘图";
            case "draw_size" -> "选择绘图尺寸";
            case "persona_switch" -> "切换人设";
            case "audio_transcribe" -> "语音转文字";
            case "image_action" -> "图片处理";
            case "document_summary" -> "文档总结";
            case "document_question" -> "文档问答";
            case "generate_file" -> "生成文件";
            case "document_edit" -> "编辑文档";
            case "weather" -> "天气";
            default -> intent;
        };
    }

    /** 将内部回复模式转换为中文名称。 */
    private String replyModeName(String replyMode) {
        return switch (replyMode) {
            case "text" -> "文字";
            case "voice" -> "语音";
            case "both" -> "文字和语音";
            case "keep" -> "保持当前设置";
            default -> replyMode;
        };
    }

    /** 将内部音色标识转换为中文名称。 */
    private String voiceStyleName(String voiceStyle) {
        return switch (voiceStyle) {
            case "boy" -> "小男孩";
            case "girl" -> "小女孩";
            case "male" -> "成年男声";
            case "female" -> "成年女声";
            case "warm" -> "温柔";
            case "lively" -> "活泼";
            case "default" -> "默认";
            default -> voiceStyle;
        };
    }

}
