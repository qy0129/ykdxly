package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.calculator.CalculatorService;
import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.feature.document.DocumentService;
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
import com.example.ilink.tools.finance.ExpenseSplitTool;
import com.example.ilink.tools.image.DrawTool;
import com.example.ilink.tools.image.ImageAnalysisTool;
import com.example.ilink.tools.image.ImageEditTool;
import com.example.ilink.tools.math.CalculatorTool;
import com.example.ilink.tools.persona.PersonaSwitchTool;
import com.example.ilink.tools.planning.DeadlineCountdownTool;
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
    private final PlanWorkflow planWorkflow;
    private final CalculatorService calculatorService;
    private final CalendarWorkflow calendarWorkflow;
    private final HealthDietWorkflow healthDietWorkflow;
    private final TravelWorkflow travelWorkflow;
    private final NearbyFoodWorkflow nearbyFoodWorkflow;

    /** 注入所有业务服务，保持本类只负责请求编排。 */
    public UserRequestHandler(ChatHistoryStore chatHistory, UserSessionStore sessions,
                              DocumentSessionStore documentSessions,
                              IntentRecognizer intentRecognizer, ChatService chatService,
                              WeatherService weatherService, MediaStore mediaStore,
                              ReplySender replySender, ToolManager toolManager,
                              PlanWorkflow planWorkflow, CalculatorService calculatorService,
                              CalendarWorkflow calendarWorkflow, HealthDietWorkflow healthDietWorkflow,
                              TravelWorkflow travelWorkflow, NearbyFoodWorkflow nearbyFoodWorkflow) {
        this.chatHistory = chatHistory;
        this.sessions = sessions;
        this.documentSessions = documentSessions;
        this.intentRecognizer = intentRecognizer;
        this.chatService = chatService;
        this.weatherService = weatherService;
        this.mediaStore = mediaStore;
        this.replySender = replySender;
        this.toolManager = toolManager;
        this.planWorkflow = planWorkflow;
        this.calculatorService = calculatorService;
        this.calendarWorkflow = calendarWorkflow;
        this.healthDietWorkflow = healthDietWorkflow;
        this.travelWorkflow = travelWorkflow;
        this.nearbyFoodWorkflow = nearbyFoodWorkflow;
    }
    /** 识别用户意图并调用对应功能处理器。 */
    public void handle(ILinkClient client, String userId, String text) throws Exception {
        // 只对未完成会话使用本地状态机；所有新请求都必须先经过大模型语义路由。
        if (sessions.hasPendingWeatherLocations(userId)) {
            handleWeatherLocationSelection(client, userId, text);
            return;
        }
        if (planWorkflow.hasPendingPlan(userId)) {
            planWorkflow.completePendingPlan(client, userId, text);
            return;
        }
        if (planWorkflow.hasPendingCalendarSync(userId)) {
            planWorkflow.completeCalendarSync(client, userId, text);
            return;
        }
        if (calendarWorkflow.hasPending(userId)) {
            calendarWorkflow.handle(client, userId, text);
            return;
        }
        if (healthDietWorkflow.hasPending(userId)) {
            healthDietWorkflow.handlePending(client, userId, text);
            return;
        }
        if (nearbyFoodWorkflow.hasPendingLocation(userId)) {
            nearbyFoodWorkflow.handleLocationSelection(client, userId, text);
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
            case "task_plan" -> planWorkflow.createPlan(client, userId, text, route);
            case "travel_plan" -> travelWorkflow.handle(client, userId, route);
            case "diet_plan" -> healthDietWorkflow.handle(client, userId, route);
            case "nearby_food" -> nearbyFoodWorkflow.handle(client, userId, route);
            case "calendar_event" -> calendarWorkflow.handle(client, userId, text, route);
            case "planning_capabilities" -> replySender.sendReply(client, userId, planningCapabilitiesText(),
                    route.replyMode(), route.voiceStyle());
            case "plan_adjust" -> planWorkflow.adjustPlan(client, userId, text, route);
            case "plan_progress" -> planWorkflow.queryProgress(client, userId, text, route);
            case "expense_split" -> handleExpenseSplit(client, userId, text, route);
            case "deadline_countdown" -> handleDeadlineCountdown(client, userId, text, route);
            case "calculator" -> {
                String reply = calculatorService.execute(userId, text);
                chatHistory.add(userId, text, reply);
                replySender.applyReplyMode(userId, route.replyMode());
                replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            }
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

    /** 调用费用分摊工具，处理多人 AA 和不同付款金额的结算。 */
    private void handleExpenseSplit(ILinkClient client, String userId, String userText,
                                    IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("request", userText);
        ToolResult result = toolManager.execute(
                ExpenseSplitTool.NAME, new ToolContext(userId), arguments);
        chatHistory.add(userId, userText, result.output());
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
    }

    /** 调用截止时间工具，返回用户距离目标时间的剩余时长。 */
    private void handleDeadlineCountdown(ILinkClient client, String userId, String userText,
                                         IntentResult route) throws Exception {
        if (route.planDeadline().isBlank()) {
            replySender.sendReply(client, userId, "请告诉我具体截止时间，例如“明天下午六点”。",
                    route.replyMode(), route.voiceStyle());
            return;
        }
        JsonObject arguments = new JsonObject();
        arguments.addProperty("deadline", route.planDeadline());
        ToolResult result = toolManager.execute(
                DeadlineCountdownTool.NAME, new ToolContext(userId), arguments);
        chatHistory.add(userId, userText, result.output());
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
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

    /** 处理文档问答、总结、生成和编辑请求。 */
    private void handleDocumentAction(ILinkClient client, String userId, String userText,
                                      IntentResult route) throws Exception {
        DocumentRecord document = documentSessions.get(userId);
        String pendingImage = sessions.peekPendingImage(userId);
        String cachedImageAnalysis = usableImageAnalysis(sessions.getLastImageAnalysis(userId));
        boolean hasImageSource = pendingImage != null
                || (cachedImageAnalysis != null && explicitlyReferencesImage(userText));
        boolean imageToNewDocument = isImageToNewDocumentRequest(
                userText, route.intent(), hasImageSource);
        if (document == null && !"generate_file".equals(route.intent()) && !imageToNewDocument) {
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

        // ── 确定输出格式 ──
        // 格式转换兜底：如果用户明确要求转格式，强制走编辑工具（不管意图识别结果）
        String toolName;
        String outputType;
        boolean isFormatConversion = document != null
                && userText.matches(".*(?:转[成为换]|改[成为]|变[成为]|导出[为成]?).*(?:Word|word|WORD|PDF|pdf|Excel|excel|PPT|ppt|DOCX|docx|xlsx|XLSX|pptx|PPTX|txt|TXT|md|csv).*");
        if (!imageToNewDocument && (isFormatConversion || "document_edit".equals(route.intent()))) {
            toolName = DocumentEditTool.NAME;
            String specified = route.outputFileType();
            // 从用户文本中推断输出格式
            if (specified == null || specified.isBlank() || "none".equals(specified)) {
                if (userText.matches(".*(?:Word|word|WORD|DOCX|docx).*")) specified = "docx";
                else if (userText.matches(".*(?:PDF|pdf).*")) specified = "pdf";
                else if (userText.matches(".*(?:Excel|excel|XLSX|xlsx).*")) specified = "xlsx";
                else if (userText.matches(".*(?:PPT|ppt|PPTX|pptx).*")) specified = "pptx";
                else if (userText.matches(".*(?:TXT|txt|文本).*")) specified = "txt";
            }
            outputType = (specified != null && !specified.isBlank() && !"none".equals(specified))
                    ? specified
                    : (document != null ? document.extension() : "docx");
        } else {
            toolName = DocumentGenerateTool.NAME;
            outputType = imageToNewDocument && userText.matches(".*(?:表格|电子表格|Excel|excel|XLSX|xlsx).*") ? "xlsx"
                    : "pdf".equals(route.outputFileType()) ? "pdf"
                    : "xlsx".equals(route.outputFileType()) ? "xlsx"
                    : "pptx".equals(route.outputFileType()) ? "pptx"
                    : "docx";
        }

        JsonObject arguments = new JsonObject();
        arguments.addProperty("request", userText);
        arguments.addProperty("output_type", outputType);
        if (imageToNewDocument) {
            System.out.println("[文档路由] 数据源=本轮图片，动作=新建 " + outputType + " 文件");
            String imageContent = cachedImageAnalysis;
            if (imageContent == null) {
                JsonObject imageArguments = new JsonObject();
                imageArguments.addProperty("request",
                        "完整识别图片中的所有文字、表格、行列、数字和单位。按原始顺序输出，不要省略，不要引用以前对话。");
                imageArguments.addProperty("mode", "analyze");
                ToolResult imageResult = toolManager.execute(
                        ImageAnalysisTool.NAME, new ToolContext(userId), imageArguments);
                imageContent = imageResult.success() ? usableImageAnalysis(imageResult.output()) : null;
                if (imageContent == null) {
                    replySender.sendReply(client, userId,
                            "本轮图片识别请求超时或失败，且没有可复用的识别缓存。图片仍已保留，请稍后直接回复“重试生成表格”。");
                    return;
                }
            } else {
                System.out.println("[文档路由] 已复用图片首次识别缓存，不再重复调用视觉模型");
            }
            arguments.addProperty("source_content", imageContent);
            arguments.addProperty("source_name", "用户刚发送的图片");
        } else {
            String lastImage = sessions.getLastImage(userId);
            if (lastImage != null) {
                arguments.addProperty("image_path", lastImage);
            }
        }
        ToolResult result = toolManager.execute(toolName, new ToolContext(userId), arguments);
        if (!result.success()) {
            replySender.sendReply(client, userId, result.output());
            return;
        }

        DocumentToolOutput output = result.dataAs(DocumentToolOutput.class);
        Path saved = mediaStore.save(userId, "file", output.bytes(), output.extension());
        chatHistory.addMedia(userId, "机器人生成文件", saved.toString(), output.caption());
        client.sendFile(userId, output.bytes(), output.fileName(), output.caption());

        // 更新会话中的文档（下次编辑基于最新版本）
        String newText = output.content();
        if (newText == null || newText.isBlank()) {
            newText = "[" + output.fileName() + " 已生成]";
        }
        documentSessions.set(userId, new DocumentRecord(
                output.fileName(), output.extension(), saved.toString(), newText));
        if (imageToNewDocument) {
            sessions.clearPendingImage(userId);
        }
    }

    static String usableImageAnalysis(String analysis) {
        if (analysis == null || analysis.isBlank()) return null;
        String normalized = analysis.strip();
        return normalized.startsWith("图片分析失败") ? null : normalized;
    }

    static boolean explicitlyReferencesImage(String text) {
        return text != null && text.matches(
                ".*(?:图片|照片|截图|拍照|这张图|刚才的图|上一张图|图中内容|识别内容).*"
        );
    }

    static boolean isImageToNewDocumentRequest(String text, String intent, boolean hasPendingImage) {
        if (!hasPendingImage || text == null) return false;
        boolean insertsIntoExisting = text.matches(
                ".*(?:插入|添加到|放到|放入|放在).*(?:当前|原来|已有|文档|文件|第\\s*[0-9一二两三四五六七八九十百]+\\s*页).*"
        );
        if (insertsIntoExisting) return false;
        boolean mentionsFile = text.matches(
                ".*(?:表格|电子表格|Excel|excel|XLSX|xlsx|Word|word|DOCX|docx|PDF|pdf|文档|文件).*"
        );
        boolean asksToCreate = text.matches(
                ".*(?:生成|制作|整理|做成|转成|转换成|写成|导出).*"
        );
        boolean referencesImage = text.matches(
                ".*(?:图片|照片|截图|拍照|这张|上面|刚才|识别内容).*"
        );
        return mentionsFile && asksToCreate
                && (referencesImage || "generate_file".equals(intent) || "document_edit".equals(intent));
    }

    /** 根据意图结果调用基础计算工具。 */
    private void handleCalculator(ILinkClient client, String userId,
                                  IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("operation", route.calculationOperation().isBlank()
                ? "add" : route.calculationOperation());
        arguments.addProperty("left", route.calculationLeft().isBlank()
                ? "0" : route.calculationLeft());
        arguments.addProperty("right", route.calculationRight().isBlank()
                ? "0" : route.calculationRight());
        arguments.addProperty("quantity", integerValue(route.calculationQuantity(), 1));
        arguments.addProperty("unit_price", route.calculationUnitPrice().isBlank()
                ? "0" : route.calculationUnitPrice());
        arguments.addProperty("discount_percent", route.calculationDiscountPercent().isBlank()
                ? "0" : route.calculationDiscountPercent());

        ToolResult result = toolManager.execute(
                CalculatorTool.NAME, new ToolContext(userId), arguments);
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(),
                route.replyMode(), route.voiceStyle());
    }

    /** 将意图中的数量文本转换为整数。 */
    private int integerValue(String value, int defaultValue) {
        try {
            return value == null || value.isBlank() ? defaultValue : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
            case "travel_plan" -> "出行规划";
            case "diet_plan" -> "饮食规划";
            case "nearby_food" -> "附近美食";
            case "calendar_event" -> "日历事件";
            case "planning_capabilities" -> "规划能力说明";
            case "task_plan" -> "制定计划";
            case "plan_adjust" -> "调整计划";
            case "plan_progress" -> "查询计划进度";
            case "expense_split" -> "费用分摊";
            case "deadline_countdown" -> "截止时间倒计时";
            case "calculator" -> "基础计算";
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

    /** 判断用户是否在询问规划能力，而不是要立即创建某个具体计划。 */
    private boolean isPlanningCapabilityQuestion(String text) {
        return text.contains("可以帮我做什么规划") || text.contains("能帮我做什么规划")
                || text.contains("有哪些规划功能") || text.contains("规划功能有哪些");
    }

    /** 仅在用户主动询问时展示，保持日常对话界面简洁。 */
    private String planningCapabilitiesText() {
        return "我可以帮你做这些规划：\n"
                + "1. 学习与阅读：拆分目标、安排每日任务、跟踪进度。\n"
                + "2. 健康饮食：制定餐食安排、用餐提醒，并按目标推荐外卖。\n"
                + "3. 日程提醒：记录一次性、每天、每周、每月、每年的事项，到点主动提醒。\n"
                + "4. 出行行程：安排出发时间、路线和地点标记地图。\n"
                + "5. 生活事件：缴费、家庭活动、纪念日、体检、宠物护理等都可以直接记到日历。\n\n"
                + "例如：\n"
                + "“明天早上 8 点提醒我吃药”\n"
                + "“帮我安排两周阅读计划”\n"
                + "“下周六从北京去天津怎么安排”";
    }

}
