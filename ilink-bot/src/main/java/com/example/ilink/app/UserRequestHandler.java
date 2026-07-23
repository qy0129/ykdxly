package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.calculator.CalculatorService;
import com.example.ilink.feature.image.GeneratedImage;
import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.routing.IntentContext;
import com.example.ilink.routing.IntentAction;
import com.example.ilink.routing.IntentPlan;
import com.example.ilink.routing.IntentPolicy;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户文本请求处理器。
 *
 * <p>先调用唯一的意图识别入口 {@link IntentRecognizer}，再按动作计划
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
    private final ActionPlanExecutor actionPlanExecutor = new ActionPlanExecutor();
    private final Map<String, PendingFileExport> pendingFileExports = new ConcurrentHashMap<>();

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
        PendingFileExport pendingFileExport = pendingFileExports.get(userId);
        if (pendingFileExport != null) {
            if (IntentPolicy.isFileTypeAnswer(text)) {
                pendingFileExports.remove(userId, pendingFileExport);
                handleDocumentAction(client, userId, pendingFileExport.userText(),
                        pendingFileExport.route(), IntentPolicy.explicitOutputFileType(text));
                return;
            }
            if ("取消".equals(text.trim())) {
                pendingFileExports.remove(userId, pendingFileExport);
                replySender.sendReply(client, userId, "已取消生成文件。");
                return;
            }
            // 用户提出了新要求，放弃旧的格式确认，避免状态劫持后续对话。
            pendingFileExports.remove(userId, pendingFileExport);
        }

        // 只对未完成会话使用本地状态机；所有新请求都必须先经过大模型语义路由。
        if (sessions.hasPendingWeatherLocations(userId)) {
            handleWeatherLocationSelection(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (planWorkflow.hasPendingPlan(userId)) {
            planWorkflow.completePendingPlan(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (planWorkflow.hasPendingCalendarSync(userId)) {
            planWorkflow.completeCalendarSync(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (calendarWorkflow.hasPending(userId)) {
            calendarWorkflow.handle(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (healthDietWorkflow.hasPending(userId)) {
            healthDietWorkflow.handlePending(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (travelWorkflow.hasPendingLocation(userId)) {
            travelWorkflow.handleLocationSelection(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }
        if (nearbyFoodWorkflow.hasPendingLocation(userId)) {
            nearbyFoodWorkflow.handleLocationSelection(client, userId, text);
            resumeActionPlan(client, userId);
            return;
        }

        // 根据当前用户的临时状态构造上下文，让意图识别知道用户正在处理什么。
        IntentContext context = new IntentContext(
                sessions.peekPendingImage(userId) != null,
                sessions.getLastImage(userId) != null,
                sessions.peekPendingDraw(userId) != null,
                documentSessions.get(userId) != null);
        IntentPlan plan = intentRecognizer.recognize(userId, text, context);
        if (plan == null || plan.isEmpty()) {
            replySender.sendReply(client, userId, "网络波动了，请再发一次～");
            return;
        }

        System.out.println("[意图识别] 共识别 " + plan.actions().size() + " 个动作："
                + plan.actions().stream().map(action -> intentName(action.route().intent())).toList());
        if (plan.actions().size() > 1) {
            String actionNames = String.join("、", plan.actions().stream()
                    .map(action -> intentName(action.route().intent())).toList());
            replySender.sendReply(client, userId, "我识别到 " + plan.actions().size()
                    + " 项要求：" + actionNames + "。现在依次处理。");
        }
        actionPlanExecutor.start(userId, plan,
                action -> executeAction(client, userId, action),
                () -> hasBlockingPending(userId),
                (action, error) -> handleActionFailure(client, userId, action, error));
    }

    /** 调用一个动作对应的原有业务处理器，动作之间由统一执行器负责排序。 */
    private void executeAction(ILinkClient client, String userId, IntentAction action) throws Exception {
        IntentResult route = action.route();
        String actionText = action.requestText();
        System.out.println("[动作执行] 意图=" + intentName(route.intent())
                + "，要求=" + actionText
                + "，回复方式=" + replyModeName(route.replyMode())
                + "，音色=" + voiceStyleName(route.voiceStyle()));
        switch (route.intent()) {
            case "draw" -> handleDraw(client, userId, actionText, route);
            case "draw_size" -> handleDrawSize(client, userId, route);
            case "persona_switch" -> handlePersonaSwitch(client, userId, actionText, route);
            case "audio_transcribe" -> handleAudioTranscribe(client, userId, route);
            case "image_action" -> handleImageAction(client, userId, route);
            case "weather" -> handleWeather(client, userId, actionText, route);
            case "task_plan" -> planWorkflow.createPlan(client, userId, actionText, route);
            case "travel_plan" -> travelWorkflow.handle(client, userId, route);
            case "diet_plan" -> healthDietWorkflow.handle(client, userId, route);
            case "nearby_food" -> nearbyFoodWorkflow.handle(client, userId, route);
            case "calendar_event" -> calendarWorkflow.handle(client, userId, actionText, route);
            case "planning_capabilities" -> replySender.sendReply(client, userId, planningCapabilitiesText(),
                    route.replyMode(), route.voiceStyle());
            case "plan_adjust" -> planWorkflow.adjustPlan(client, userId, actionText, route);
            case "plan_progress" -> planWorkflow.queryProgress(client, userId, actionText, route);
            case "expense_split" -> handleExpenseSplit(client, userId, actionText, route);
            case "deadline_countdown" -> handleDeadlineCountdown(client, userId, actionText, route);
            case "calculator" -> {
                String reply = calculatorService.execute(userId, actionText);
                chatHistory.add(userId, actionText, reply);
                replySender.applyReplyMode(userId, route.replyMode());
                replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            }
            case "document_summary", "document_question", "generate_file", "document_edit" ->
                    handleDocumentAction(client, userId, actionText, route);
            default -> {
                String reply = chatService.chat(userId, actionText);
                if (reply == null || reply.isBlank()) {
                    reply = "网络波动了，请再发一次～";
                }
                chatHistory.add(userId, actionText, reply);
                replySender.applyReplyMode(userId, route.replyMode());
                System.out.println("[机器人回复] " + reply);
                replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
            }
        }
    }

    /** 当前补充会话结束后继续执行同一段话中尚未完成的动作。 */
    private void resumeActionPlan(ILinkClient client, String userId) throws Exception {
        if (hasBlockingPending(userId)) return;
        actionPlanExecutor.resume(userId,
                action -> executeAction(client, userId, action),
                () -> hasBlockingPending(userId),
                (action, error) -> handleActionFailure(client, userId, action, error));
    }

    /** 判断是否有必须先由用户补充地点、时间或偏好的工作流。 */
    private boolean hasBlockingPending(String userId) {
        return sessions.hasPendingWeatherLocations(userId)
                || planWorkflow.hasPendingPlan(userId)
                || planWorkflow.hasPendingCalendarSync(userId)
                || calendarWorkflow.hasPending(userId)
                || healthDietWorkflow.hasPending(userId)
                || travelWorkflow.hasPendingLocation(userId)
                || nearbyFoodWorkflow.hasPendingLocation(userId);
    }

    /** 记录单个动作的错误并继续后续动作，避免一个工具失败导致整段请求中断。 */
    private void handleActionFailure(ILinkClient client, String userId,
                                     IntentAction action, Exception error) throws Exception {
        String actionName = intentName(action.route().intent());
        System.err.println("[动作执行] " + actionName + "失败: " + error.getMessage());
        replySender.sendReply(client, userId, actionName + "执行失败，已继续处理其他要求。");
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
        GeneratedImage image = result.dataAs(GeneratedImage.class);
        if (result.success() && image != null) {
            Path saved = mediaStore.save(userId, "image", image.bytes(), image.extension());
            documentSessions.clear(userId);
            sessions.setLastImage(userId, saved.toString());
            chatHistory.addMedia(userId, "图片", saved.toString(), route.cnDescription());
            client.sendImage(userId, image.bytes(), image.fileName("draw"), route.cnDescription());
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
        GeneratedImage image = result.dataAs(GeneratedImage.class);
        if (result.success() && image != null) {
            Path saved = mediaStore.save(userId, "image", image.bytes(), image.extension());
            documentSessions.clear(userId);
            sessions.setLastImage(userId, saved.toString());
            chatHistory.addMedia(userId, "图片", saved.toString(), "已按你的要求生成");
            client.sendImage(userId, image.bytes(), image.fileName("draw"), "已按你的要求生成");
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
        // 人格定义会决定之后语音回复的音色，但切换人格本身只做文字确认。
        client.sendText(userId, result.output());
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
            GeneratedImage edited = result.dataAs(GeneratedImage.class);
            if (result.success() && edited != null) {
                Path saved = mediaStore.save(userId, "image", edited.bytes(), edited.extension());
                documentSessions.clear(userId);
                sessions.setLastImage(userId, saved.toString());
                chatHistory.addMedia(userId, "图片", saved.toString(), "已根据用户要求修改图片");
                client.sendImage(userId, edited.bytes(), edited.fileName("edited"), "已完成图片修改");
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
        int dayOffset = WeatherService.dayOffset(weatherDay);
        String reply = weatherService.queryWeather(location, dayOffset, WeatherService.period(weatherDay));
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
        handleDocumentAction(client, userId, userText, route, route.outputFileType());
    }

    /** 处理文档动作；forcedOutputType 用于继续上一轮文件格式确认。 */
    private void handleDocumentAction(ILinkClient client, String userId, String userText,
                                      IntentResult route, String forcedOutputType) throws Exception {
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

        String outputType = "pdf".equals(forcedOutputType) ? "pdf"
                : "docx".equals(forcedOutputType) ? "docx" : "none";
        if ("generate_file".equals(route.intent()) && "none".equals(outputType)) {
            pendingFileExports.put(userId, new PendingFileExport(userText, route));
            replySender.sendReply(client, userId, "你想生成 PDF 还是 Word 文件？回复格式即可，回复“取消”可结束。");
            return;
        }
        if ("none".equals(outputType)) {
            outputType = defaultDocumentOutputType(document);
        }
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

    private record PendingFileExport(String userText, IntentResult route) {
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
