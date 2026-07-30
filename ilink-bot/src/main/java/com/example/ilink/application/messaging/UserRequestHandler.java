package com.example.ilink.application.messaging;

import com.example.ilink.application.tooling.ActionPlanExecutor;
import com.example.ilink.application.workflow.calendar.CalendarWorkflow;
import com.example.ilink.application.workflow.food.FoodOrderWorkflow;
import com.example.ilink.application.workflow.food.HealthDietWorkflow;
import com.example.ilink.application.workflow.food.NearbyFoodWorkflow;
import com.example.ilink.application.workflow.planning.PlanWorkflow;
import com.example.ilink.application.workflow.life.LifeWorkflow;
import com.example.ilink.application.workflow.travel.TaxiWorkflow;
import com.example.ilink.application.workflow.travel.TravelWorkflow;
import com.example.ilink.application.workflow.visual.VisualCardWorkflow;
import com.example.ilink.application.executive.ExecutiveRuntime;
import com.example.ilink.application.executive.ExecutiveTaskService;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.ContextManager;
import com.example.ilink.application.conversation.ConversationContext;
import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.application.conversation.KnowledgeContext;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.conversation.SuggestedActionStore;
import com.example.ilink.capabilities.chat.ChatService;
import com.example.ilink.capabilities.automation.AutomationWorkflow;
import com.example.ilink.capabilities.calculator.CalculatorService;
import com.example.ilink.capabilities.image.GeneratedImage;
import com.example.ilink.capabilities.express.ExpressService;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.mail.QqMailService;
import com.example.ilink.capabilities.location.LocationService;
import com.example.ilink.capabilities.radar.InterestRadarService;
import com.example.ilink.capabilities.media.MediaKnowledgeResponse;
import com.example.ilink.capabilities.media.MediaKnowledgeService;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.planning.TodoBatchParser;
import com.example.ilink.capabilities.planning.TodoDraft;
import com.example.ilink.capabilities.weather.WeatherLocation;
import com.example.ilink.capabilities.weather.WeatherService;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.capabilities.web.NewsSearchService;
import com.example.ilink.capabilities.web.BilibiliSearchService;
import com.example.ilink.capabilities.web.WebSearchService;
import com.example.ilink.capabilities.documents.DocumentRecord;
import com.example.ilink.capabilities.web.SearchResult;
import com.example.ilink.application.routing.IntentContext;
import com.example.ilink.application.routing.IntentAction;
import com.example.ilink.application.routing.IntentPlan;
import com.example.ilink.application.routing.IntentPolicy;
import com.example.ilink.application.routing.IntentRecognizer;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.application.routing.RoutePlanReviewer;
import com.example.ilink.application.routing.RoutingContext;
import com.example.ilink.application.routing.CapabilityContractValidator;
import com.example.ilink.application.routing.DrawSizeParser;
import com.example.ilink.platform.media.MediaStore;
import com.example.ilink.capabilities.audio.AudioTranscribeTool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.documents.DocumentEditTool;
import com.example.ilink.capabilities.documents.DocumentGenerateTool;
import com.example.ilink.capabilities.documents.DocumentQATool;
import com.example.ilink.capabilities.documents.DocumentToolOutput;
import com.example.ilink.capabilities.finance.ExpenseSplitTool;
import com.example.ilink.capabilities.express.ExpressTool;
import com.example.ilink.capabilities.image.DrawTool;
import com.example.ilink.capabilities.image.ImageAnalysisTool;
import com.example.ilink.capabilities.image.ImageEditTool;
import com.example.ilink.capabilities.calculator.CalculatorTool;
import com.example.ilink.capabilities.persona.PersonaSwitchTool;
import com.example.ilink.capabilities.planning.DeadlineCountdownTool;
import com.example.ilink.capabilities.planning.DateTimeParser;
import com.example.ilink.capabilities.weather.WeatherTool;
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 用户文本请求处理器。
 *
 * <p>先调用唯一的意图识别入口 {@link IntentRecognizer}，再按动作计划
 * 调用聊天、绘图、图片、音频或文档功能。该类负责流程协调，具体 API 调用
 * 放在各 feature 服务中。</p>
 */
public final class UserRequestHandler {

    private static final java.util.regex.Pattern WEATHER_CURRENT_LOCATION = java.util.regex.Pattern.compile(
            "^\\s*我(?:现在|目前|当前)?在\\s*([^，,。！？?、]{2,40})");
    private static final java.util.regex.Pattern WEATHER_MARKER = java.util.regex.Pattern.compile(
            "(天气预报|天气怎么样|天气如何|查天气|查询天气|气温多少|温度多少|会不会下雨|天气|气温|温度)");

    private final ChatHistoryStore chatHistory;
    private final UserSessionStore sessions;
    private final DocumentSessionStore documentSessions;
    private final IntentRecognizer intentRecognizer;
    private final ChatService chatService;
    private final WeatherService weatherService;
    private final MediaStore mediaStore;
    private final ReplySender replySender;
    private final ToolManager toolManager;
    private final RoutePlanReviewer routePlanReviewer;
    private final ContextManager contextManager;
    private final PlanWorkflow planWorkflow;
    private final LifeWorkflow lifeWorkflow;
    private final CalculatorService calculatorService;
    private final CalendarWorkflow calendarWorkflow;
    private final HealthDietWorkflow healthDietWorkflow;
    private final TravelWorkflow travelWorkflow;
    private final NearbyFoodWorkflow nearbyFoodWorkflow;
    private final FoodOrderWorkflow foodOrderWorkflow;
    private final TaxiWorkflow taxiWorkflow;
    private final MemoryService memoryService;
    private final TodoService todoService;
    private final WebSearchService webSearchService;
    private final NewsSearchService newsSearchService;
    private final BilibiliSearchService bilibiliSearchService;
    private final MediaKnowledgeService mediaKnowledgeService;
    private final QqMailService qqMailService;
    private final VisualCardWorkflow visualCardWorkflow;
    private final ExecutiveRuntime executiveRuntime;
    private final AutomationWorkflow automationWorkflow;
    private final InterestRadarService interestRadarService;
    private final LocationService locationService;
    private final ActionPlanExecutor actionPlanExecutor = new ActionPlanExecutor();
    private final CapabilityContractValidator capabilityValidator = new CapabilityContractValidator();
    private final TodoBatchParser todoBatchParser = new TodoBatchParser();
    private final SuggestedActionStore suggestedActions = new SuggestedActionStore();
    private final ThreadLocal<Boolean> executingSuggestedAction = ThreadLocal.withInitial(() -> false);

    /** 注入所有业务服务，保持本类只负责请求编排。 */
    public UserRequestHandler(ChatHistoryStore chatHistory, UserSessionStore sessions,
                              DocumentSessionStore documentSessions,
                              IntentRecognizer intentRecognizer, ChatService chatService,
                              WeatherService weatherService, MediaStore mediaStore,
                              ReplySender replySender, ToolManager toolManager,
                              RoutePlanReviewer routePlanReviewer,
                              ContextManager contextManager,
                              PlanWorkflow planWorkflow, LifeWorkflow lifeWorkflow,
                              CalculatorService calculatorService,
                               CalendarWorkflow calendarWorkflow, HealthDietWorkflow healthDietWorkflow,
                               TravelWorkflow travelWorkflow, NearbyFoodWorkflow nearbyFoodWorkflow,
                               FoodOrderWorkflow foodOrderWorkflow,
                              TaxiWorkflow taxiWorkflow,
                              MemoryService memoryService, TodoService todoService,
                              WebSearchService webSearchService, NewsSearchService newsSearchService,
                              BilibiliSearchService bilibiliSearchService,
                              MediaKnowledgeService mediaKnowledgeService, QqMailService qqMailService,
                             VisualCardWorkflow visualCardWorkflow,
                              ExecutiveRuntime executiveRuntime, AutomationWorkflow automationWorkflow,
                              InterestRadarService interestRadarService, LocationService locationService) {
        this.chatHistory = chatHistory;
        this.sessions = sessions;
        this.documentSessions = documentSessions;
        this.intentRecognizer = intentRecognizer;
        this.chatService = chatService;
        this.weatherService = weatherService;
        this.mediaStore = mediaStore;
        this.replySender = replySender;
        this.toolManager = toolManager;
        this.routePlanReviewer = routePlanReviewer;
        this.contextManager = contextManager;
        this.planWorkflow = planWorkflow;
        this.lifeWorkflow = lifeWorkflow;
        this.calculatorService = calculatorService;
        this.calendarWorkflow = calendarWorkflow;
        this.healthDietWorkflow = healthDietWorkflow;
        this.travelWorkflow = travelWorkflow;
        this.nearbyFoodWorkflow = nearbyFoodWorkflow;
        this.foodOrderWorkflow = foodOrderWorkflow;
        this.taxiWorkflow = taxiWorkflow;
        this.memoryService = memoryService;
        this.todoService = todoService;
        this.webSearchService = webSearchService;
        this.newsSearchService = newsSearchService;
        this.bilibiliSearchService = bilibiliSearchService;
        this.mediaKnowledgeService = mediaKnowledgeService;
        this.qqMailService = qqMailService;
        this.visualCardWorkflow = visualCardWorkflow;
        this.executiveRuntime = executiveRuntime;
        this.automationWorkflow = automationWorkflow;
        this.interestRadarService = interestRadarService;
        this.locationService = locationService;
    }

    /** 提供给消息入口的轻量状态查询，避免把具体工作流状态暴露给路由层。 */
    public boolean hasPendingInteraction(String userId) {
        return hasBlockingPending(userId)
                || sessions.getPendingFileExport(userId) != null
                || lifeWorkflow.hasPendingStudyPlan(userId)
                || visualCardWorkflow.hasPending(userId);
    }

    /** 识别用户意图并调用对应功能处理器。 */
    public void handle(AgentContext agentContext, String text) throws Exception {
        ReplyChannel client = agentContext.replyChannel();
        String userId = agentContext.principalId();
        if (handleReplyPreference(client, userId, text)) return;
        if (!executingSuggestedAction.get() && handleSuggestedActionReply(agentContext, text)) return;
        if (handleLocationRequest(client, userId, text)) return;
        String executiveReply = executiveRuntime.handleCommand(userId, text);
        if (executiveReply != null) {
            replySender.sendReply(client, userId, executiveReply);
            return;
        }
        String radarReply = interestRadarService.handleCommand(userId, text);
        if (radarReply != null) {
            replySender.sendReply(client, userId, radarReply);
            return;
        }
        if (automationWorkflow.looksLikeAutomation(text)) {
            submitAutomation(client, userId, "automation_research", text);
            return;
        }
        if (handleFailedActionRetry(agentContext, text)) return;

        UserSessionStore.PendingFileExport pendingFileExport = sessions.getPendingFileExport(userId);
        if (pendingFileExport != null) {
            if (IntentPolicy.isFileTypeAnswer(text)) {
                sessions.clearPendingFileExport(userId);
                handleDocumentAction(client, userId, pendingFileExport.userText(),
                        pendingFileExport.route(), IntentPolicy.explicitOutputFileType(text));
                resumeActionPlan(agentContext);
                return;
            }
            if ("取消".equals(text.trim())) {
                sessions.clearPendingFileExport(userId);
                actionPlanExecutor.cancel(userId);
                replySender.sendReply(client, userId, "已取消生成文件。");
                return;
            }
        }

        if (handlePendingDrawContinuation(agentContext, text)) return;

        if (lifeWorkflow.hasPendingStudyPlan(userId)
                && lifeWorkflow.acceptsPendingReply(userId, text)) {
            lifeWorkflow.completePendingStudyPlan(client, userId, text);
            resumeActionPlan(agentContext);
            return;
        }

        if (hasBlockingPending(userId) && !acceptsBlockingReply(userId, text)) {
            clearBlockingPending(userId);
            actionPlanExecutor.cancel(userId);
        }

        if (handleRepeatCommand(client, userId, text)) return;
        if (IntentPolicy.isCasualGreeting(text)) {
            nearbyFoodWorkflow.clearPending(userId);
            replySender.sendReply(client, userId, "你好，我在。有什么想让我帮你处理的？");
            return;
        }
        if (visualCardWorkflow.hasPending(userId) && visualCardWorkflow.handle(agentContext, text)) {
            resumeActionPlan(agentContext);
            return;
        }
        if (handlePendingExpress(client, userId, text)) {
            resumeActionPlan(agentContext);
            return;
        }
        if (IntentPolicy.isExplicitImageEdit(text) && !hasCurrentImage(userId)) {
            replySender.sendReply(client, userId,
                    "请先发送需要修改的图片。发送后直接告诉我怎么改，例如“把头发改成白色”。");
            return;
        }

        if (pendingFileExport != null) {
            // 用户提出了新要求，已在入口统一清除旧状态。
            sessions.clearPendingFileExport(userId);
        }

        // 只对未完成会话使用本地状态机；所有新请求都必须先经过大模型语义路由。
        if (sessions.hasPendingWeatherLocations(userId)) {
            handleWeatherLocationSelection(client, userId, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (planWorkflow.hasPendingPlan(userId)) {
            planWorkflow.completePendingPlan(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (planWorkflow.hasPendingCalendarSync(userId)) {
            planWorkflow.completeCalendarSync(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (calendarWorkflow.hasPending(userId)) {
            IntentResult pendingRoute = null;
            if (!"取消".equals(text.trim())) {
                IntentContext pendingContext = new IntentContext(
                        sessions.peekPendingImage(userId) != null,
                        sessions.getLastImage(userId) != null,
                        sessions.peekPendingDraw(userId) != null,
                        documentSessions.get(userId) != null,
                        true);
                publishActivity("正在调用意图模型", "model", "running", Map.of("model", "Qwen"));
                IntentPlan pendingPlan = intentRecognizer.recognize(userId, text,
                        buildRoutingContext(agentContext, pendingContext, text));
                if (pendingPlan != null) {
                    pendingRoute = pendingPlan.actions().stream()
                            .map(IntentAction::route)
                            .filter(route -> "calendar_event".equals(route.intent()))
                            .findFirst()
                            .orElse(null);
                }
            }
            calendarWorkflow.completePending(agentContext, text, pendingRoute);
            resumeActionPlan(agentContext);
            return;
        }
        if (healthDietWorkflow.hasPending(userId)) {
            healthDietWorkflow.handlePending(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (travelWorkflow.hasPendingLocation(userId)) {
            travelWorkflow.handleLocationSelection(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (nearbyFoodWorkflow.hasPendingLocation(userId)) {
            nearbyFoodWorkflow.handleLocationSelection(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (foodOrderWorkflow.hasPending(userId)) {
            foodOrderWorkflow.handlePending(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }
        if (taxiWorkflow.hasPending(userId)) {
            taxiWorkflow.handlePending(agentContext, text);
            resumeActionPlan(agentContext);
            return;
        }

        // 高频待办请求走本地解析，避免为明确的提醒语句构建完整上下文并调用多轮路由模型。
        if (looksLikeFastTodo(text)) {
            handleTodoAction(client, userId, text);
            return;
        }

        // 根据当前用户的临时状态构造上下文，让意图识别知道用户正在处理什么。
        IntentContext intentContext = new IntentContext(
                sessions.peekPendingImage(userId) != null,
                sessions.getLastImage(userId) != null,
                sessions.peekPendingDraw(userId) != null,
                documentSessions.get(userId) != null,
                false);
        publishActivity("正在分析请求", "model", "running", Map.of("model", "Qwen"));
        IntentPlan plan = intentRecognizer.recognize(userId, text,
                buildRoutingContext(agentContext, intentContext, text));
        if (plan == null || plan.isEmpty()) {
            replySender.sendReply(client, userId, "网络波动了，请再发一次～");
            return;
        }
        RoutePlanReviewer.Review review = routePlanReviewer.review(plan, intentContext);
        if (review.needsInput()) {
            replySender.sendReply(client, userId, review.prompt());
            return;
        }
        plan = review.plan();

        String recognizedActions = String.join("、", plan.actions().stream()
                .map(action -> intentName(action.route().intent())).toList());
        publishActivity("已完成意图分析", "model", "success", Map.of(
                "model", "Qwen", "actions", recognizedActions,
                "actionCount", plan.actions().size()));

        System.out.println("[意图识别] 共识别 " + plan.actions().size() + " 个动作："
                + plan.actions().stream().map(action -> intentName(action.route().intent())).toList());
        plan = coalesceDependentActions(plan);
        if (plan.actions().size() > 1) {
            String actionNames = String.join("、", plan.actions().stream()
                    .map(action -> intentName(action.route().intent())).toList());
            replySender.sendReply(client, userId, "我识别到 " + plan.actions().size()
                    + " 项要求：" + actionNames + "。现在依次处理。");
        }
        actionPlanExecutor.start(userId, plan,
                action -> executeAction(agentContext, action),
                () -> hasBlockingPending(userId),
                (action, error) -> handleActionFailure(client, userId, action, error));
    }

    /** 重发上一条回复，不调用大模型，避免被误识别为邮箱查询。 */
    private boolean handleRepeatCommand(ReplyChannel client, String userId, String text) throws Exception {
        if (!IntentPolicy.isRepeatRequest(text)) return false;
        String previous = replySender.lastText(userId);
        if (previous.isBlank()) previous = chatHistory.lastAssistantMessage(userId);
        if (previous.isBlank()) {
            replySender.sendReply(client, userId, "我暂时找不到可以重发的上一条回复。 ");
        } else {
            client.sendText(userId, previous);
            replySender.markSent(userId);
            replySender.rememberText(userId, previous);
        }
        return true;
    }

    /** 地图分享链接和主动重新定位属于确定性输入，不进入大模型路由。 */
    private boolean handleLocationRequest(ReplyChannel client, String userId, String text) throws Exception {
        LocationService.LinkUpdate linkUpdate = locationService.updateFromSharedLink(userId, text);
        if (linkUpdate.recognized()) {
            replySender.sendReply(client, userId, linkUpdate.message());
            return true;
        }
        String normalized = text == null ? "" : text.trim().replaceAll("[，。！？!?]+$", "");
        if (!normalized.matches("^(获取我的位置|获取当前位置|更新位置|重新定位|定位我|授权位置)$")) {
            return false;
        }
        String url = locationService.createAuthorizationUrl(userId);
        if (url.isBlank()) {
            replySender.sendReply(client, userId,
                    "定位服务尚未就绪，请检查高德 Key 和 location HTTPS 配置。");
        } else {
            replySender.sendReply(client, userId, "请打开链接授权当前位置：\n" + url);
        }
        return true;
    }

    /** 待选尺寸属于封闭状态，直接本地续办，不再请求路由模型。 */
    private boolean handlePendingDrawContinuation(AgentContext context, String text) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        UserSessionStore.PendingDrawRequest pending = sessions.getPendingDraw(userId);
        if (pending == null) return false;

        String imageSize = DrawSizeParser.parse(text);
        if (!"none".equals(imageSize)) {
            sessions.clearPendingDraw(userId);
            generatePendingImage(client, userId, pending, imageSize);
            resumeActionPlan(context);
            return true;
        }
        if (DrawSizeParser.isCancel(text)) {
            sessions.clearPendingDraw(userId);
            actionPlanExecutor.cancel(userId);
            replySender.sendReply(client, userId, "已取消这次绘图。");
            return true;
        }

        // 不符合尺寸选项时按新请求处理，防止旧状态劫持后续对话。
        sessions.clearPendingDraw(userId);
        actionPlanExecutor.cancel(userId);
        return false;
    }

    private boolean hasCurrentImage(String userId) {
        UserSessionStore.ImageReference reference = sessions.resolveCurrentImage(userId);
        return reference != null && !reference.path().isBlank() && Files.isRegularFile(Path.of(reference.path()));
    }

    /** 执行已经由统一路由识别出的长期记忆动作。 */
    private boolean handleMemoryCommand(ReplyChannel client, String userId, String text) throws Exception {
        String normalized = text.trim();
        if (normalized.matches("^(请)?(帮我)?记住.*") || normalized.startsWith("以后记得")) {
            String homeLocation = memoryService.extractHomeLocation(normalized);
            String currentLocation = memoryService.extractCurrentLocation(normalized);
            if (!homeLocation.isBlank() || !currentLocation.isBlank()) {
                String invalidLocation = verifyRememberedLocation(homeLocation);
                if (invalidLocation.isBlank() && !currentLocation.isBlank()
                        && !currentLocation.equals(homeLocation)) {
                    invalidLocation = verifyRememberedLocation(currentLocation);
                }
                if (!invalidLocation.isBlank()) {
                    replySender.sendReply(client, userId, invalidLocation);
                    return true;
                }

                String reply = homeLocation.isBlank()
                        ? "好，我记住你现在在“" + currentLocation + "”。"
                        : memoryService.remember(userId, normalized);
                sessions.setCurrentLocation(userId,
                        currentLocation.isBlank() ? homeLocation : currentLocation);
                replySender.sendReply(client, userId, reply);
                return true;
            }
            String reply = memoryService.remember(userId, normalized);
            String location = memoryService.value(userId, "home_location");
            if (!location.isBlank()) sessions.setCurrentLocation(userId, location);
            replySender.sendReply(client, userId, reply);
            return true;
        }
        if (normalized.contains("忘记") || normalized.contains("忘掉") || normalized.contains("不要记得")) {
            replySender.sendReply(client, userId, memoryService.forget(userId, normalized));
            return true;
        }
        if (normalized.matches(".*(你记得我什么|记得我的什么|我的偏好是什么|我的长期记忆).*")) {
            replySender.sendReply(client, userId, memoryService.describe(userId));
            return true;
        }
        return false;
    }

    /** 记忆中的地点必须先经过地点服务核验，避免保存整句或不存在的地点。 */
    private String verifyRememberedLocation(String location) throws Exception {
        if (location == null || location.isBlank()) return "";
        try {
            List<WeatherLocation> locations = weatherService.searchLocations(location);
            if (locations.isEmpty()) {
                return "我识别到地点“" + location + "”，但没有查到这个地点。请确认具体地点后再让我记住。";
            }
            if (locations.size() > 1 && WeatherService.clearlyPrimary(locations) == null) {
                return buildWeatherLocationChoices(locations);
            }
            return "";
        } catch (Exception error) {
            return "我暂时无法核对地点“" + location + "”是否存在，请稍后再试。";
        }
    }

    /** 执行已经由统一路由识别出的待办动作。 */
    private void handleTodoAction(ReplyChannel client, String userId, String text) throws Exception {
        String normalized = text == null ? "" : text.trim();
        if (normalized.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我要创建、查看、完成还是删除待办。");
            return;
        }

        if (normalized.matches("^(查看|查询|列出|打开)?(我的)?待办(事项|列表)?$|^我还有什么待办.*")) {
            replySender.sendReply(client, userId, todoService.list(userId));
            return;
        }
        if (normalized.matches("^(完成|办完|搞定)(这个|最后一个|最新的)?待办.*")) {
            String keyword = normalized.replaceFirst("^(完成|办完|搞定)(这个|最后一个|最新的)?待办[：:，, ]*", "").trim();
            replySender.sendReply(client, userId, todoService.complete(userId, keyword));
            return;
        }
        if (normalized.matches("^(取消|删除)(这个|最后一个|最新的)?待办.*")) {
            String keyword = normalized.replaceFirst("^(取消|删除)(这个|最后一个|最新的)?待办[：:，, ]*", "").trim();
            replySender.sendReply(client, userId, todoService.cancel(userId, keyword));
            return;
        }
        if (normalized.matches("^(请)?(帮我)?(添加|新增|创建|记)(一个|个)?待办.*|^待办[：:].*")
                || normalized.matches(".*(提醒我|别忘了|记得|记一下).*")) {
            createTodo(client, userId, normalized);
            return;
        }
        replySender.sendReply(client, userId, "请明确要创建、查看、完成还是删除待办。");
    }

    private boolean isExpressCommand(String text) {
        if (text.matches(".*(打车|叫车|出租车|司机|行程).*")) return false;
        String trackingNo = ExpressService.extractTrackingNo(text);
        boolean hasKeyword = text.matches(".*(快递|物流|包裹|运单|订单|订单号|到哪了|到哪里了|是否签收).*" );
        if (hasKeyword) return true;
        if (trackingNo.isBlank()) return false;
        return text.replaceAll("[\\s，,。？?]", "").equalsIgnoreCase(trackingNo);
    }

    private void queryExpress(ReplyChannel client, String userId, String text) throws Exception {
        String trackingNo = ExpressService.extractTrackingNo(text);
        String orderNo = ExpressService.extractOrderNo(text);
        String phone = extractExpressPhone(text);
        if (sessions.peekPendingImage(userId) != null
                && trackingNo.isBlank() && orderNo.isBlank() && phone.isBlank()) {
            sessions.setPendingExpress(userId, "reading_image", "");
            handleExpressImage(client, userId);
            return;
        }

        UserSessionStore.PendingExpressState pending = sessions.getPendingExpress(userId);
        if (trackingNo.isBlank() && !phone.isBlank() && pending != null
                && "awaiting_retry".equals(pending.stage())) {
            trackingNo = pending.referenceNo();
        }
        if (trackingNo.isBlank() && pending != null && "awaiting_retry".equals(pending.stage())
                && text.trim().matches("重试|再查一次|继续查询")) {
            trackingNo = pending.referenceNo();
        }
        if (trackingNo.isBlank() && phone.isBlank()) {
            sessions.setPendingExpress(userId, "awaiting_input", "");
            replySender.sendReply(client, userId,
                    "我记住你正在查询快递。请发送快递单号、运单号，或者直接发包含单号的图片。 ");
            return;
        }
        executeExpressQuery(client, userId, text, trackingNo, phone);
    }

    private void executeExpressQuery(ReplyChannel client, String userId, String sourceText,
                                     String trackingNo, String phone) throws Exception {
        JsonObject arguments = new JsonObject();
        if (!trackingNo.isBlank()) arguments.addProperty("tracking_no", trackingNo);
        if (!phone.isBlank()) arguments.addProperty("phone", phone);
        ToolResult result = toolManager.execute(ExpressTool.NAME, new ToolContext(userId), arguments);
        if (result.success()) sessions.clearPendingExpress(userId);
        else if (!trackingNo.isBlank()) sessions.setPendingExpress(userId, "awaiting_retry", trackingNo);
        chatHistory.add(userId, sourceText, result.output());
        visualCardWorkflow.sendTextResult(client, userId, "物流进度", trackingNo, result.output());
        if (result.success()) {
            ExpressTool.ExpressOutput output = result.dataAs(ExpressTool.ExpressOutput.class);
            if (output != null && output.qrBytes() != null && output.qrBytes().length > 0) {
                replySender.sendImage(client, userId, output.qrBytes(),
                        "express_qr.png", "扫码查看物流追踪页面");
            }
        }
    }

    private boolean handlePendingExpress(ReplyChannel client, String userId, String text) throws Exception {
        if (!sessions.hasPendingExpress(userId)) return false;
        String normalized = text == null ? "" : text.trim();
        if ("取消".equals(normalized) || normalized.matches("(取消|结束)(快递|物流)?查询")) {
            sessions.clearPendingExpress(userId);
            replySender.sendReply(client, userId, "已取消这次快递查询。 ");
            return true;
        }
        boolean related = isExpressCommand(normalized)
                || !ExpressService.extractTrackingNo(normalized).isBlank()
                || !ExpressService.extractOrderNo(normalized).isBlank()
                || !extractExpressPhone(normalized).isBlank()
                || normalized.matches("重试|再查一次|继续查询");
        if (!related) {
            sessions.clearPendingExpress(userId);
            return false;
        }
        queryExpress(client, userId, normalized);
        return true;
    }

    /** 用户处于快递查询状态时，图片到达后自动识别并继续查询。 */
    public void handleExpressImage(ReplyChannel client, String userId) throws Exception {
        sessions.setPendingExpress(userId, "reading_image", "");
        JsonObject arguments = new JsonObject();
        arguments.addProperty("request", "只识别图片中的快递信息。请严格按以下格式输出，不要解释：\n"
                + "快递单号：\n订单号：\n快递公司：\n没有的字段保持为空。");
        arguments.addProperty("mode", "analyze");
        ToolResult analysis = toolManager.execute(
                ImageAnalysisTool.NAME, new ToolContext(userId), arguments);
        if (!analysis.success()) {
            sessions.setPendingExpress(userId, "awaiting_input", "");
            replySender.sendReply(client, userId, "这张图片暂时没有识别成功，请发一张更清晰、包含完整单号的图片。 ");
            return;
        }

        String recognized = analysis.output();
        String imagePath = analysis.dataAs(String.class);
        if (imagePath != null && !imagePath.isBlank()) {
            chatHistory.addMedia(userId, "快递图片", imagePath, recognized);
        }
        String trackingNo = ExpressService.extractLabeledTrackingNo(recognized);
        String orderNo = ExpressService.extractOrderNo(recognized);
        if (!trackingNo.isBlank()) {
            executeExpressQuery(client, userId, "图片识别到快递单号：" + trackingNo, trackingNo, "");
            return;
        }
        if (!orderNo.isBlank()) {
            sessions.setPendingExpress(userId, "awaiting_tracking_no", orderNo);
            replySender.sendReply(client, userId, "我从图片里识别到了订单号“" + orderNo
                    + "”，并已经记住。但查询物流需要快递单号或运单号，请继续发给我。 ");
            return;
        }
        sessions.setPendingExpress(userId, "awaiting_input", "");
        replySender.sendReply(client, userId,
                "图片里只识别到“订单号”等文字，没有识别到实际号码。请发完整清晰的截图，或者直接输入快递单号。 ");
    }

    private String extractExpressPhone(String text) {
        java.util.regex.Matcher full = java.util.regex.Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)")
                .matcher(text);
        if (full.find()) return full.group(1);
        java.util.regex.Matcher tail = java.util.regex.Pattern.compile("(?:尾号|后四位)[^0-9]*(\\d{4})(?!\\d)")
                .matcher(text);
        return tail.find() ? tail.group(1) : "";
    }

    private void createTodo(ReplyChannel client, String userId, String text) throws Exception {
        List<TodoDraft> drafts = todoBatchParser.parse(text);
        if (drafts.isEmpty()) {
            replySender.sendReply(client, userId, "请告诉我待办的具体内容。");
            return;
        }
        List<com.example.ilink.capabilities.planning.TodoItem> created =
                todoService.createBatch(userId, drafts, 30);
        StringBuilder reply = new StringBuilder("好，已经记到待办里了：");
        for (int index = 0; index < created.size(); index++) {
            var todo = created.get(index);
            if (index > 0) reply.append('\n');
            reply.append(index + 1).append(". ").append(todo.title());
            if (todo.dueAt() != null) {
                reply.append("（").append(todo.dueAt().format(DateTimeFormatter.ofPattern("M月d日 HH:mm"))).append("）");
            }
        }
        replySender.sendReply(client, userId, reply.toString());
    }

    private boolean looksLikeFastTodo(String text) {
        if (text == null || text.isBlank()) return false;
        String value = text.trim();
        if (value.matches("^(查看|查询|列出|打开)?(我的)?待办(事项|列表)?$|^我还有什么待办.*")) return true;
        if (value.matches("^(完成|办完|搞定|取消|删除).*(待办|任务).*")) return true;
        // 多个明确时间点组成的句子就是待办，即使用户没有重复说“提醒我”。
        if (todoBatchParser.looksLikeCompound(value)) return true;
        return value.matches(".*(提醒我|别忘了|记得|记一下|添加待办|新增待办|创建待办|记个待办|安排一个待办).*" )
                && !value.matches(".*(天气|快递|新闻|打车|外卖|搜索|文件|图片).*" );
    }

    private void searchNews(ReplyChannel client, String userId, String text) throws Exception {
        String query = text.replaceFirst("^(请)?(帮我)?(查|查询|搜索|看看|获取)?(一下)?", "")
                .replaceAll("(今天|今日|最新|实时)?的?(新闻|资讯|热搜)", "").trim();
        if (query.isBlank()) query = "最新新闻";
        if (text.matches(".*(今天|今日|最新|实时).*")) query += " when:1d";
        try {
            List<SearchResult> results = newsSearchService.search(query, Config.WEB_SEARCH_RESULT_LIMIT);
            String reply = formatSearchResults("实时新闻", results);
            visualCardWorkflow.sendSearchResults(client, userId, "实时新闻", results, reply);
        } catch (Exception e) {
            System.err.println("[实时新闻] 查询失败: " + e.getMessage());
            replySender.sendReply(client, userId, "这次实时新闻查询没有成功，我目前无法确认最新内容，请稍后再试。");
        }
    }

    private void searchWeb(ReplyChannel client, String userId, String text) throws Exception {
        String query = text.replaceFirst("^(请)?(帮我)?(联网搜索|联网查|上网查|网页搜索|实时搜索)(一下)?[：:，, ]*", "").trim();
        if (query.isBlank()) {
            replySender.sendReply(client, userId, "请告诉我需要联网查询什么内容。");
            return;
        }
        try {
            List<SearchResult> results = webSearchService.search(query, Config.WEB_SEARCH_RESULT_LIMIT);
            String reply = formatSearchResults("联网搜索结果", results);
            visualCardWorkflow.sendSearchResults(client, userId, "联网搜索结果", results, reply);
        } catch (Exception e) {
            System.err.println("[联网搜索] 查询失败: " + e.getMessage());
            replySender.sendReply(client, userId, "这次联网查询没有成功，我不会用旧知识冒充实时结果，请稍后再试。");
        }
    }

    private String formatSearchResults(String heading, List<SearchResult> results) {
        if (results.isEmpty()) return heading + "暂时没有找到可靠结果。";
        StringBuilder reply = new StringBuilder(heading).append("：\n");
        for (int index = 0; index < results.size(); index++) {
            SearchResult result = results.get(index);
            reply.append(index + 1).append(". ").append(result.title()).append('\n');
            if (!result.summary().isBlank()) reply.append(shorten(result.summary(), 180)).append('\n');
            reply.append("来源：").append(result.source().isBlank() ? "网页" : result.source());
            if (!result.publishedAt().isBlank()) reply.append("｜").append(result.publishedAt());
            reply.append('\n').append(result.url()).append("\n\n");
        }
        return reply.toString().trim();
    }

    private String shorten(String text, int maxLength) {
        String value = text.replaceAll("\\s+", " ").trim();
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }

    public static String extractWeatherLocation(String text) {
        if (text == null || text.isBlank()) return "";
        java.util.regex.Matcher currentLocation = WEATHER_CURRENT_LOCATION.matcher(text);
        if (currentLocation.find()) {
            return currentLocation.group(1)
                    .replaceFirst("(?:今天|明天|后天|天气|气温|温度|会不会下雨).*", "")
                    .trim();
        }
        java.util.regex.Matcher weatherMarker = WEATHER_MARKER.matcher(text);
        String locationText = weatherMarker.find() ? text.substring(0, weatherMarker.start()) : text;
        return locationText.replaceFirst("^(请)?(帮我)?(查|查询|看看|看一下)?", "")
                .replaceFirst("^(我(?:现在|目前|当前)?在|当前位置是|我的位置是)\\s*", "")
                .replaceAll("(今天|今日|明天|明日|后天|未来七天|未来7天)", "")
                .replaceAll("(?:(?:\\d{4})年)?\\d{1,2}月\\d{1,2}(?:日|号)?", "")
                .replaceAll("(上午|中午|下午|傍晚|晚上|今晚)", "")
                .replaceAll("[？?，,。；;、 ]+", "")
                .replaceFirst("的$", "")
                .trim();
    }

    /** 调用一个动作对应的原有业务处理器，动作之间由统一执行器负责排序。 */
    private void executeAction(AgentContext context, IntentAction action) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        IntentResult route = action.route();
        String actionText = action.requestText();
        CapabilityContractValidator.Validation validation = capabilityValidator.validate(
                actionText, route, new CapabilityContractValidator.Context(
                        sessions.getPendingDraw(userId) != null,
                        hasCurrentImage(userId),
                        documentSessions.get(userId) != null));
        if (!validation.allowed()) {
            System.out.println("[能力校验] 拒绝意图=" + route.intent() + "，要求=" + actionText);
            if (validation.decision() == CapabilityContractValidator.Decision.FALLBACK_CHAT) {
                executeChatAction(context, actionText, route);
            } else {
                replySender.sendReply(client, userId, validation.message());
            }
            return;
        }
        System.out.println("[动作执行] 意图=" + intentName(route.intent())
                + "，要求=" + actionText
                + "，回复方式=" + replyModeName(route.replyMode())
                + "，音色=" + voiceStyleName(route.voiceStyle()));
        String actionName = intentName(route.intent());
        publishActivity("执行流程：" + actionName, "workflow", "running", Map.of(
                "intent", route.intent(), "action", actionName));
        try {
            switch (route.intent()) {
            case "chat" -> executeChatAction(context, actionText, route);
            case "draw" -> handleDraw(client, userId, actionText, route);
            case "draw_size" -> handleDrawSize(client, userId, route);
            case "persona_switch" -> handlePersonaSwitch(client, userId, actionText, route);
            case "audio_transcribe" -> handleAudioTranscribe(client, userId, route);
            case "image_action" -> handleImageAction(client, userId, actionText, route);
            case "weather" -> handleWeather(client, userId, actionText, route);
            case "task_plan" -> planWorkflow.createPlan(client, userId, actionText, route);
            case "study_plan" -> lifeWorkflow.startStudyPlan(client, userId, actionText, route);
            case "travel_plan" -> travelWorkflow.handle(client, userId, route);
            case "taxi_trip" -> taxiWorkflow.handle(client, userId, route, actionText);
            case "diet_plan" -> healthDietWorkflow.handle(client, userId, route);
            case "nearby_food" -> nearbyFoodWorkflow.handle(client, userId, route);
            case "calendar_event" -> calendarWorkflow.handle(client, userId, actionText, route);
            case "bilibili_search" -> searchBilibili(client, userId, route);
            case "media_lookup" -> lookupMedia(client, userId, actionText, route);
            case "email_query" -> queryEmail(client, userId, route);
            case "todo" -> handleTodoAction(client, userId, actionText);
            case "express_query" -> queryExpress(client, userId, actionText);
            case "news_search" -> searchNews(client, userId, actionText);
            case "web_search" -> searchWeb(client, userId, actionText);
            case "automation_research", "job_search", "jd_analysis", "resume_match" ->
                    submitAutomation(client, userId, route.intent(), actionText);
            case "automation_status" -> replySender.sendReply(client, userId,
                    executiveRuntime.handleCommand(userId, "任务状态"));
            case "memory" -> {
                if (!handleMemoryCommand(client, userId, actionText)) executeChatAction(context, actionText, route);
            }
            case "visual_card" -> {
                if (!visualCardWorkflow.handle(context, actionText)) executeChatAction(context, actionText, route);
            }
            case "planning_capabilities" -> replySender.sendReply(client, userId, planningCapabilitiesText(),
                    route.replyMode(), route.voiceStyle());
            case "plan_adjust" -> planWorkflow.adjustPlan(client, userId, actionText, route);
            case "plan_progress" -> planWorkflow.queryProgress(client, userId, actionText, route);
            case "life_task_update" -> lifeWorkflow.updateTask(client, userId, actionText);
            case "life_plan_list" -> lifeWorkflow.listPlans(client, userId);
            case "life_plan_select" -> lifeWorkflow.selectPlan(client, userId, actionText);
            case "today_learning" -> lifeWorkflow.todayLearning(client, userId);
            case "daily_reflection" -> lifeWorkflow.reflectToday(client, userId);
            case "reflection_history" -> lifeWorkflow.reflectionHistory(client, userId);
            case "expense_split" -> handleExpenseSplit(client, userId, actionText, route);
            case "food_order" -> handleFoodOrder(client, userId, actionText, route);
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
                    sendChatReply(client, userId, actionText, route);
                }
            }
            publishActivity("流程完成：" + actionName, "workflow", "success", Map.of(
                    "intent", route.intent(), "action", actionName));
        } catch (Exception error) {
            publishActivity("流程失败：" + actionName, "workflow", "failed", Map.of(
                    "intent", route.intent(), "action", actionName));
            throw error;
        }
    }

    private void executeChatAction(AgentContext context, String text, IntentResult route) throws Exception {
        sendChatReply(context.replyChannel(), context.principalId(), text, route);
    }

    private void submitAutomation(ReplyChannel client, String userId, String intent, String text) throws Exception {
        ExecutiveTaskService.Submission submission = automationWorkflow.submit(userId, intent, text);
        String message = submission.created()
                ? "已创建自动化任务：" + submission.task().id() + "\n我会在后台执行并主动发送结果。"
                : "这个自动化任务已经存在：" + submission.task().id()
                        + "\n当前状态：" + submission.task().status();
        replySender.sendReply(client, userId, message);
    }

    /** 把会话、位置、时间和所有等待状态合并成一次路由快照。 */
    private RoutingContext buildRoutingContext(AgentContext agentContext, IntentContext mediaContext, String query) {
        String userId = agentContext.principalId();
        ConversationContext conv = contextManager.buildConversation(userId,
                agentContext.channel() == ChannelType.WEB ? agentContext.conversationId() : null);
        KnowledgeContext kn = needsKnowledgeContext(query)
                ? contextManager.buildKnowledge(userId, query)
                : KnowledgeContext.empty(query);
        Map<String, Boolean> pending = new LinkedHashMap<>();
        pending.put("draw_size", sessions.getPendingDraw(userId) != null);
        pending.put("express", sessions.hasPendingExpress(userId));
        pending.put("weather_location", sessions.hasPendingWeatherLocations(userId));
        pending.put("task_plan", planWorkflow.hasPendingPlan(userId));
        pending.put("calendar_sync", planWorkflow.hasPendingCalendarSync(userId));
        pending.put("calendar", calendarWorkflow.hasPending(userId));
        pending.put("diet", healthDietWorkflow.hasPending(userId));
        pending.put("travel_location", travelWorkflow.hasPendingLocation(userId));
        pending.put("nearby_food_location", nearbyFoodWorkflow.hasPendingLocation(userId));
        pending.put("food_order", foodOrderWorkflow.hasPending(userId));
        pending.put("taxi", taxiWorkflow.hasPending(userId));
        pending.put("visual_card", visualCardWorkflow.hasPending(userId));
        pending.put("file_export", sessions.hasPendingFileExport(userId));
        return new RoutingContext(
                conv.persona(), contextManager.buildMemory(userId).prompt(), conv.summary(),
                conv.recentMessages(), kn.prompt(),
                sessions.getCurrentLocation(userId),
                sessions.getCurrentCity(userId), ZonedDateTime.now(ZoneId.systemDefault()),
                mediaContext, pending);
    }

    private boolean needsKnowledgeContext(String query) {
        if (query == null || query.isBlank()) return false;
        return query.matches(".*(根据|结合|参考|文件|文档|资料|知识库|我发的|刚才发的|上面的内容).*" );
    }

    private void publishActivity(String content, String phase, String status,
                                 Map<String, Object> details) {
        Map<String, Object> metadata = new LinkedHashMap<>(details);
        metadata.put("phase", phase);
        metadata.put("status", status);
        RequestLogContext.publish(new AgentEvent(AgentEvent.Type.TOOL_ACTIVITY, content, metadata));
    }

    private void sendChatReply(ReplyChannel client, String userId, String text,
                               IntentResult route) throws Exception {
        String reply = chatService.chat(userId, text);
        if (reply == null || reply.isBlank()) reply = "网络波动了，请再发一次～";
        if (isExecutableOffer(reply, text)) suggestedActions.offer(userId, text);
        chatHistory.add(userId, text, reply);
        replySender.applyReplyMode(userId, route.replyMode());
        System.out.println("[机器人回复] " + reply);
        replySender.sendReply(client, userId, reply, route.replyMode(), route.voiceStyle());
    }

    private boolean handleSuggestedActionReply(AgentContext context, String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.matches("(不要|不用|不需要|算了|先不用|取消)[。！! ]*")) {
            SuggestedActionStore.SuggestedAction rejected = suggestedActions.consume(context.principalId());
            if (rejected == null) return false;
            replySender.sendReply(context.replyChannel(), context.principalId(), "好，这项建议已取消。");
            return true;
        }
        if (!value.matches("(需要|要|好的|好|可以|行|确定|就这样|帮我做|继续)[。！! ]*")) return false;
        SuggestedActionStore.SuggestedAction action = suggestedActions.consume(context.principalId());
        if (action == null) {
            replySender.sendReply(context.replyChannel(), context.principalId(),
                    "请告诉我具体需要做什么，我没有找到尚未执行的上一条建议。");
            return true;
        }
        executingSuggestedAction.set(true);
        try {
            handle(context, action.requestText());
        } finally {
            executingSuggestedAction.remove();
        }
        return true;
    }

    private boolean handleReplyPreference(ReplyChannel client, String userId, String text) throws Exception {
        String value = text == null ? "" : text.trim();
        if (value.matches(".*(以后|今后|从现在开始|默认).*(都用|使用|改成)?.*(语音).*(回复|回答)?.*")) {
            replySender.setDefaultReplyMode(userId, "voice");
            replySender.sendReply(client, userId, "已将默认回复方式改为语音。", "text", "default");
            return true;
        }
        if (value.matches(".*(以后|今后|从现在开始|默认).*(都用|使用|改成)?.*(文字|文本).*(回复|回答)?.*")) {
            replySender.setDefaultReplyMode(userId, "text");
            replySender.sendReply(client, userId, "已将默认回复方式改为文字。", "text", "default");
            return true;
        }
        return false;
    }

    private boolean isExecutableOffer(String reply, String requestText) {
        if (reply == null || requestText == null) return false;
        boolean asks = reply.matches("(?s).*(需要我|要我|是否需要我|要不要我).*(帮|处理|创建|查询|安排|执行).*" );
        boolean actionable = requestText.matches(".*(提醒|待办|日历|天气|打车|快递|搜索|查询|创建|生成|规划|文件|图片|外卖).*" );
        return asks && actionable;
    }

    /** 当前补充会话结束后继续执行同一段话中尚未完成的动作。 */
    private void resumeActionPlan(AgentContext context) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        if (hasBlockingPending(userId)) return;
        actionPlanExecutor.resume(userId,
                action -> executeAction(context, action),
                () -> hasBlockingPending(userId),
                (action, error) -> handleActionFailure(client, userId, action, error));
    }

    /** 判断是否有必须先由用户补充地点、时间或偏好的工作流。 */
    private boolean hasBlockingPending(String userId) {
        return sessions.getPendingDraw(userId) != null
                || sessions.hasPendingExpress(userId)
                || sessions.hasPendingWeatherLocations(userId)
                || planWorkflow.hasPendingPlan(userId)
                || planWorkflow.hasPendingCalendarSync(userId)
                || calendarWorkflow.hasPending(userId)
                || healthDietWorkflow.hasPending(userId)
                || travelWorkflow.hasPendingLocation(userId)
                || nearbyFoodWorkflow.hasPendingLocation(userId)
                || foodOrderWorkflow.hasPending(userId)
                || taxiWorkflow.hasPending(userId)
                || visualCardWorkflow.hasPending(userId)
                || sessions.hasPendingFileExport(userId);
    }

    private boolean handleFailedActionRetry(AgentContext context, String text) throws Exception {
        ReplyChannel client = context.replyChannel();
        String userId = context.principalId();
        if (!actionPlanExecutor.hasFailedAction(userId)
                || !text.trim().matches("(重试|再试一次|重试刚才失败的.*)")) return false;
        actionPlanExecutor.retryFailed(userId,
                action -> executeAction(context, action),
                (action, error) -> handleActionFailure(client, userId, action, error));
        replySender.sendReply(client, userId, "已重试刚才失败的" + "操作。" );
        return true;
    }

    /** 仅把真正属于当前补充流程的输入交给工作流，其他输入视为新需求。 */
    private boolean acceptsBlockingReply(String userId, String text) {
        String value = text == null ? "" : text.trim();
        if ("取消".equals(value)) return true;
        if (sessions.getPendingDraw(userId) != null) {
            return !"none".equals(DrawSizeParser.parse(value)) || DrawSizeParser.isCancel(value);
        }
        if (sessions.hasPendingExpress(userId)) {
            return isExpressCommand(value) || !ExpressService.extractTrackingNo(value).isBlank()
                    || !ExpressService.extractOrderNo(value).isBlank() || !extractExpressPhone(value).isBlank()
                    || value.matches("重试|再查一次|继续查询");
        }
        if (sessions.hasPendingWeatherLocations(userId)) return value.matches("\\d+") || !looksLikeNewRequest(value);
        if (planWorkflow.hasPendingPlan(userId)) return DateTimeParser.parse(value) != null;
        if (planWorkflow.hasPendingCalendarSync(userId)) {
            return value.contains("同步") || value.contains("确认") || value.contains("记录") || value.contains("是")
                    || value.contains("否");
        }
        if (calendarWorkflow.hasPending(userId)) return value.matches("\\d+") || DateTimeParser.parse(value) != null;
        if (healthDietWorkflow.hasPending(userId)) return !looksLikeNewRequest(value);
        if (travelWorkflow.hasPendingLocation(userId)) return travelWorkflow.acceptsPendingReply(value);
        if (nearbyFoodWorkflow.hasPendingLocation(userId)) return nearbyFoodWorkflow.acceptsPendingReply(userId, value);
        if (foodOrderWorkflow.hasPending(userId)) return foodOrderWorkflow.acceptsPendingReply(value);
        if (taxiWorkflow.hasPending(userId)) {
            return taxiWorkflow.acceptsPendingReply(userId, value) && !looksLikeNewRequest(value);
        }
        if (visualCardWorkflow.hasPending(userId)) {
            return visualCardWorkflow.acceptsPendingReply(userId, value) && !looksLikeNewRequest(value);
        }
        return sessions.hasPendingFileExport(userId) && IntentPolicy.isFileTypeAnswer(value);
    }

    private boolean looksLikeNewRequest(String text) {
        if (text.matches("(?i)^(你好|您好|嗨|哈喽|hi|hello|在吗|谢谢|再见|你是谁|帮助|help)[！!。,.， ]*$")) {
            return true;
        }
        return text.matches(".*(天气|快递|物流|待办|新闻|路线|导航|日历|提醒|查一下|查询|搜索|帮我规划|点外卖).*" );
    }

    private void clearBlockingPending(String userId) {
        sessions.clearPendingDraw(userId);
        sessions.clearPendingExpress(userId);
        sessions.clearPendingWeatherLocations(userId);
        planWorkflow.clearPending(userId);
        calendarWorkflow.clearPending(userId);
        healthDietWorkflow.clearPending(userId);
        travelWorkflow.clearPending(userId);
        nearbyFoodWorkflow.clearPending(userId);
        foodOrderWorkflow.clearPending(userId);
        taxiWorkflow.clearPending(userId);
        visualCardWorkflow.clearPending(userId);
        sessions.clearPendingFileExport(userId);
    }

    /** 路线动作已负责创建带导航链接的日历，去掉同一行程的重复日历动作。 */
    private IntentPlan coalesceDependentActions(IntentPlan plan) {
        IntentAction travel = plan.actions().stream()
                .filter(action -> "travel_plan".equals(action.route().intent()))
                .filter(action -> !action.route().travelDepartureTime().isBlank())
                .findFirst().orElse(null);
        List<IntentAction> actions = travel == null ? plan.actions() : plan.actions().stream().filter(action -> {
            if (!"calendar_event".equals(action.route().intent())
                    || !"create".equals(action.route().calendarAction())) return true;
            String title = action.route().calendarTitle();
            return title == null || (!title.contains(travel.route().travelOrigin())
                    && !title.contains(travel.route().travelDestination()));
        }).toList();

        List<IntentAction> todoActions = actions.stream()
                .filter(action -> "todo".equals(action.route().intent())).toList();
        if (todoActions.size() <= 1) return actions == plan.actions() ? plan : new IntentPlan(actions);

        String mergedText = String.join("，", todoActions.stream().map(IntentAction::requestText).toList());
        IntentAction firstTodo = todoActions.getFirst();
        IntentAction merged = new IntentAction(firstTodo.requirementId(), mergedText,
                todoActions.stream().flatMap(action -> action.dependsOn().stream()).distinct().toList(),
                firstTodo.route());
        List<IntentAction> mergedActions = new java.util.ArrayList<>();
        boolean inserted = false;
        for (IntentAction action : actions) {
            if (!"todo".equals(action.route().intent())) {
                mergedActions.add(action);
            } else if (!inserted) {
                mergedActions.add(merged);
                inserted = true;
            }
        }
        return new IntentPlan(mergedActions);
    }

    /** 搜索哔哩哔哩内容；具体视频不可用时服务会返回官方搜索入口。 */
    private void searchBilibili(ReplyChannel client, String userId, IntentResult route) throws Exception {
        List<SearchResult> results = bilibiliSearchService.search(
                route.bilibiliQuery(), route.bilibiliCategory());
        String reply = bilibiliSearchService.formatReply(results);
        chatHistory.add(userId, route.bilibiliQuery(), reply);
        visualCardWorkflow.sendBilibiliResults(client, userId, results, reply);
    }

    /** 查询动漫或音乐资料，并继续提供哔哩哔哩入口。 */
    private void lookupMedia(ReplyChannel client, String userId, String requestText,
                             IntentResult route) throws Exception {
        String query = route.mediaQuery().isBlank() ? requestText : route.mediaQuery();
        MediaKnowledgeResponse knowledge = mediaKnowledgeService.lookup(
                query, route.mediaCategory(), requestText);
        List<SearchResult> videos = bilibiliSearchService.search(
                knowledge.bilibiliQuery(), knowledge.bilibiliCategory());
        String reply = knowledge.text() + "\n\n" + bilibiliSearchService.formatReply(videos);
        chatHistory.add(userId, requestText, reply);
        visualCardWorkflow.sendMediaResults(client, userId, query, knowledge.text(), videos, reply);
    }

    /** 只读查询绑定用户的 QQ 邮箱。 */
    private void queryEmail(ReplyChannel client, String userId, IntentResult route) throws Exception {
        String reply = qqMailService.query(userId, route.emailAction(), route.emailKeyword());
        chatHistory.add(userId, "QQ邮箱查询", reply);
        visualCardWorkflow.sendTextResult(client, userId, "QQ 邮箱", "邮件查询结果", reply);
    }

    /** 记录单个动作的错误并继续后续动作，避免一个工具失败导致整段请求中断。 */
    private void handleActionFailure(ReplyChannel client, String userId,
                                     IntentAction action, Exception error) throws Exception {
        String actionName = intentName(action.route().intent());
        System.err.println("[动作执行] " + actionName + "失败: " + error.getMessage());
        replySender.sendReply(client, userId, actionName + "执行失败，已继续处理其他要求。");
    }

    /** 处理绘图请求；缺少尺寸时先保存提示词，等待用户补充。 */
    private void handleDraw(ReplyChannel client, String userId, String userText,
                            IntentResult route) throws Exception {
        sessions.clearPendingDraw(userId);
        chatHistory.add(userId, userText, "[图片] " + route.cnDescription());
        replySender.applyReplyMode(userId, route.replyMode());

        if ("none".equals(route.imageSize())) {
            sessions.setPendingDraw(userId, route.enPrompt(), route.cnDescription(), userText);
            replySender.sendReply(client, userId, "请问你想要什么尺寸？方形(1:1)、竖屏(3:4)还是横屏(16:9)？");
            return;
        }

        generatePendingImage(client, userId,
                new UserSessionStore.PendingDrawRequest(route.enPrompt(), route.cnDescription(),
                        userText, Long.MAX_VALUE), route.imageSize());
    }

    /** 处理用户对上一次绘图请求补充的尺寸选择。 */
    private void handleDrawSize(ReplyChannel client, String userId, IntentResult route) throws Exception {
        UserSessionStore.PendingDrawRequest pending = sessions.getPendingDraw(userId);
        if (pending == null) {
            replySender.sendReply(client, userId, "当前没有等待确认尺寸的绘图请求");
            return;
        }
        if ("none".equals(route.imageSize())) {
            replySender.sendReply(client, userId, "请回复尺寸：方形、竖屏或横屏");
            return;
        }

        sessions.clearPendingDraw(userId);
        generatePendingImage(client, userId, pending, route.imageSize());
    }

    private void generatePendingImage(ReplyChannel client, String userId,
                                      UserSessionStore.PendingDrawRequest pending,
                                      String imageSize) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("prompt", pending.prompt());
        arguments.addProperty("image_size", imageSize);
        ToolResult result = toolManager.execute(
                DrawTool.NAME, new ToolContext(userId), arguments);
        if (result.hasMedia(GeneratedImage.class)) {
            GeneratedImage image = result.dataAs(GeneratedImage.class);
            Path saved = mediaStore.save(userId, "image", image.bytes(), image.extension());
            documentSessions.clear(userId);
            sessions.setLastImage(userId, saved.toString());
            String description = pending.description().isBlank()
                    ? "已按你的要求生成" : pending.description();
            chatHistory.addMedia(userId, "图片", saved.toString(), description);
            client.sendImage(userId, image.bytes(), image.fileName("draw"), "");
            replySender.markSent(userId);
        } else {
            String error = result.success() ? "图片服务没有返回有效图片，请稍后重试。" : result.output();
            replySender.sendReply(client, userId, error);
        }
    }

    /** 校验并切换用户当前人设。 */
    private void handlePersonaSwitch(ReplyChannel client, String userId, String userText,
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
    private void handleAudioTranscribe(ReplyChannel client, String userId,
                                       IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("source", route.audioSource());
        arguments.addProperty("index", route.audioIndex());
        ToolResult result = toolManager.execute(
                AudioTranscribeTool.NAME, new ToolContext(userId), arguments);
        client.sendText(userId, result.output());
    }

    /** 处理图片分析、解题和编辑请求。 */
    private void handleImageAction(ReplyChannel client, String userId, String actionText,
                                   IntentResult route) throws Exception {
        String imageAction = route.imageAction();
        if ((imageAction == null || imageAction.isBlank() || "none".equals(imageAction)
                || "clarify".equals(imageAction)) && IntentPolicy.isExplicitImageEdit(actionText)) {
            imageAction = "edit";
        }
        if ("clarify".equals(imageAction) || "none".equals(imageAction)) {
            replySender.sendReply(client, userId, "请告诉我想怎么处理图片：分析内容、解答题目，还是修改图片？");
            return;
        }

        if ("analyze".equals(imageAction) || "solve".equals(imageAction)) {
            JsonObject arguments = new JsonObject();
            String request = route.imagePrompt().isBlank() ? actionText : route.imagePrompt();
            arguments.addProperty("request", request);
            arguments.addProperty("mode", imageAction);
            ToolResult result = toolManager.execute(
                    ImageAnalysisTool.NAME, new ToolContext(userId), arguments);
            if (result.success()) {
                String imagePath = result.dataAs(String.class);
                chatHistory.addMedia(userId, "图片", imagePath, result.output());
            }
            replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
            return;
        }

        if ("edit".equals(imageAction)) {
            JsonObject arguments = new JsonObject();
            String prompt = route.imagePrompt().isBlank() ? actionText : route.imagePrompt();
            arguments.addProperty("prompt", prompt);
            ToolResult result = toolManager.execute(
                    ImageEditTool.NAME, new ToolContext(userId), arguments);
            if (result.hasMedia(GeneratedImage.class)) {
                GeneratedImage edited = result.dataAs(GeneratedImage.class);
                Path saved = mediaStore.save(userId, "image", edited.bytes(), edited.extension());
                documentSessions.clear(userId);
                sessions.setLastImage(userId, saved.toString());
                chatHistory.addMedia(userId, "图片", saved.toString(), "已根据用户要求修改图片");
                client.sendImage(userId, edited.bytes(), edited.fileName("edited"), "");
                replySender.markSent(userId);
            } else {
                String error = result.success() ? "图片服务没有返回有效图片，请稍后重试。" : result.output();
                replySender.sendReply(client, userId, error);
            }
            return;
        }

        replySender.sendReply(client, userId,
                "我识别到你想处理图片，但没有确定具体操作。请说明要分析、解题，还是修改图片。");
    }

    /** 调用费用分摊工具，处理多人 AA 和不同付款金额的结算。 */
    private void handleExpenseSplit(ReplyChannel client, String userId, String userText,
                                    IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("request", userText);
        ToolResult result = toolManager.execute(
                ExpenseSplitTool.NAME, new ToolContext(userId), arguments);
        chatHistory.add(userId, userText, result.output());
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(), route.replyMode(), route.voiceStyle());
    }

    /** 根据当前位置查找具体分店，并生成平台门店链接或精确搜索入口。 */
    private void handleFoodOrder(ReplyChannel client, String userId, String userText,
                                 IntentResult route) throws Exception {
        replySender.applyReplyMode(userId, route.replyMode());
        foodOrderWorkflow.handle(client, userId, route);
    }

    /** 调用截止时间工具，返回用户距离目标时间的剩余时长。 */
    private void handleDeadlineCountdown(ReplyChannel client, String userId, String userText,
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
    private void handleWeather(ReplyChannel client, String userId, String userText,
                               IntentResult route) throws Exception {
        String locationName = extractWeatherLocation(route.weatherLocation());
        if (locationName.isBlank()) locationName = extractWeatherLocation(userText);
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
    private void handleWeatherLocationSelection(ReplyChannel client, String userId, String text) throws Exception {
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
        WeatherLocation selected = WeatherService.clearlyPrimary(refinedLocations);
        if (refinedLocations.size() == 1 || selected != null) {
            sessions.clearPendingWeatherLocations(userId);
            sendWeatherReply(client, userId, text,
                    selected == null ? refinedLocations.getFirst() : selected,
                    weatherDay, "keep", "default");
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
    private void sendWeatherReply(ReplyChannel client, String userId, String userText,
                                  WeatherLocation location, String weatherDay,
                                  String replyMode, String voiceStyle) throws Exception {
        String reply = weatherService.queryWeather(location, WeatherService.date(weatherDay), WeatherService.period(weatherDay));
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
    private void handleDocumentAction(ReplyChannel client, String userId, String userText,
                                      IntentResult route) throws Exception {
        handleDocumentAction(client, userId, userText, route, route.outputFileType());
    }

    /** 处理文档动作；forcedOutputType 用于继续上一轮文件格式确认。 */
    private void handleDocumentAction(ReplyChannel client, String userId, String userText,
                                      IntentResult route, String forcedOutputType) throws Exception {
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
    private void handleCalculator(ReplyChannel client, String userId,
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
            case "taxi_trip" -> "滴滴叫车";
            case "diet_plan" -> "饮食规划";
            case "nearby_food" -> "附近美食";
            case "calendar_event" -> "日历事件";
            case "planning_capabilities" -> "规划能力说明";
            case "bilibili_search" -> "哔哩哔哩内容";
            case "media_lookup" -> "动漫音乐资料";
            case "email_query" -> "QQ邮箱查询";
            case "todo" -> "待办";
            case "express_query" -> "快递查询";
            case "news_search" -> "新闻搜索";
            case "web_search" -> "网页搜索";
            case "memory" -> "长期记忆";
            case "visual_card" -> "视觉卡片";
            case "task_plan" -> "制定计划";
            case "study_plan" -> "学习计划";
            case "plan_adjust" -> "调整计划";
            case "plan_progress" -> "查询计划进度";
            case "life_task_update" -> "更新计划任务";
            case "life_plan_list" -> "查看全部计划";
            case "life_plan_select" -> "切换计划";
            case "today_learning" -> "今日学习";
            case "daily_reflection" -> "每日复盘";
            case "reflection_history" -> "复盘历史";
            case "expense_split" -> "费用分摊";
            case "food_order" -> "点餐";
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
