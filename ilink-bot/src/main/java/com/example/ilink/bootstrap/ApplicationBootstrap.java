package com.example.ilink.bootstrap;

import com.example.ilink.adapter.inbound.http.DailyDashboardServer;
import com.example.ilink.adapter.inbound.http.ExpressHttpServer;
import com.example.ilink.adapter.inbound.http.SessionManagementServer;
import com.example.ilink.adapter.inbound.wechat.LoginQrPage;
import com.example.ilink.adapter.inbound.wechat.MessageDispatcher;
import com.example.ilink.adapter.inbound.wechat.WechatMessageAdapter;
import com.example.ilink.application.briefing.LoginBriefingService;
import com.example.ilink.application.command.CommandHandler;
import com.example.ilink.application.command.CommandRouter;
import com.example.ilink.application.conversation.AudioHistoryStore;
import com.example.ilink.application.conversation.ContextManager;
import com.example.ilink.application.conversation.NewSessionService;
import com.example.ilink.application.welcome.WelcomeHandler;
import com.example.ilink.application.conversation.CalendarSessionStore;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.DietPlanSessionStore;
import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.application.conversation.PlanSessionStore;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.memory.MemoryExtractor;
import com.example.ilink.application.conversation.SessionService;
import com.example.ilink.application.messaging.CapabilityDispatcher;
import com.example.ilink.application.messaging.MessageProcessor;
import com.example.ilink.application.messaging.MessageSerialExecutor;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.messaging.UserRequestHandler;
import com.example.ilink.application.routing.IntentRecognizer;
import com.example.ilink.application.routing.RoutePlanReviewer;
import com.example.ilink.application.tooling.ToolManager;
import com.example.ilink.application.tooling.mcp.HttpMcpClient;
import com.example.ilink.application.tooling.mcp.McpServerRegistry;
import com.example.ilink.capabilities.audio.AudioService;
import com.example.ilink.capabilities.audio.AudioTranscribeTool;
import com.example.ilink.capabilities.audio.SpeechTool;
import com.example.ilink.capabilities.calculator.AreaTool;
import com.example.ilink.capabilities.calculator.BMITool;
import com.example.ilink.capabilities.calculator.BaseConversionTool;
import com.example.ilink.capabilities.calculator.CalculatorService;
import com.example.ilink.capabilities.calculator.CalculatorTool;
import com.example.ilink.capabilities.calculator.ChineseMoneyTool;
import com.example.ilink.capabilities.calculator.CurrencyTool;
import com.example.ilink.capabilities.calculator.LengthTool;
import com.example.ilink.capabilities.calculator.MortgageTool;
import com.example.ilink.capabilities.calculator.RelationTool;
import com.example.ilink.capabilities.calculator.SpeedTool;
import com.example.ilink.capabilities.calculator.TaxTool;
import com.example.ilink.capabilities.calculator.TemperatureTool;
import com.example.ilink.capabilities.calculator.TimeTool;
import com.example.ilink.capabilities.calculator.VolumeTool;
import com.example.ilink.capabilities.calculator.WeightTool;
import com.example.ilink.capabilities.calendar.CalendarEventStore;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.application.workflow.calendar.CalendarWorkflow;
import com.example.ilink.capabilities.calendar.HolidayService;
import com.example.ilink.capabilities.chat.ChatService;
import com.example.ilink.capabilities.dashboard.DailyDashboardService;
import com.example.ilink.capabilities.documents.DocumentAiService;
import com.example.ilink.capabilities.documents.DocumentEditTool;
import com.example.ilink.capabilities.documents.DocumentGenerateTool;
import com.example.ilink.capabilities.documents.DocumentQATool;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.capabilities.documents.PlanDocumentTool;
import com.example.ilink.capabilities.documents.rag.EmbeddingService;
import com.example.ilink.capabilities.documents.rag.Retriever;
import com.example.ilink.capabilities.documents.rag.VectorStore;
import com.example.ilink.capabilities.express.ExpressPageService;
import com.example.ilink.capabilities.express.ExpressService;
import com.example.ilink.capabilities.express.ExpressTool;
import com.example.ilink.capabilities.finance.ExpenseSplitService;
import com.example.ilink.capabilities.finance.ExpenseSplitTool;
import com.example.ilink.capabilities.food.FoodDeliveryTool;
import com.example.ilink.capabilities.food.FoodOrderService;
import com.example.ilink.capabilities.food.FoodOrderTool;
import com.example.ilink.application.workflow.food.FoodOrderWorkflow;
import com.example.ilink.capabilities.food.FoodPreferenceMapper;
import com.example.ilink.application.workflow.food.HealthDietWorkflow;
import com.example.ilink.capabilities.food.NearbyFoodTool;
import com.example.ilink.application.workflow.food.NearbyFoodWorkflow;
import com.example.ilink.capabilities.image.DrawTool;
import com.example.ilink.capabilities.image.ImageAnalysisTool;
import com.example.ilink.capabilities.image.ImageEditTool;
import com.example.ilink.capabilities.image.ImageService;
import com.example.ilink.capabilities.image.VisionService;
import com.example.ilink.capabilities.mail.QqMailService;
import com.example.ilink.capabilities.media.BangumiService;
import com.example.ilink.capabilities.media.LrcLibService;
import com.example.ilink.capabilities.media.MediaKnowledgeService;
import com.example.ilink.capabilities.media.MusicBrainzService;
import com.example.ilink.capabilities.memory.MemoryService;
import com.example.ilink.capabilities.persona.PersonaSwitchTool;
import com.example.ilink.capabilities.planning.DateTimeTool;
import com.example.ilink.capabilities.planning.DeadlineCountdownTool;
import com.example.ilink.capabilities.planning.PlanAdjustTool;
import com.example.ilink.capabilities.planning.PlanProgressTool;
import com.example.ilink.application.workflow.planning.PlanWorkflow;
import com.example.ilink.capabilities.planning.TaskDecompositionTool;
import com.example.ilink.capabilities.planning.TaskPlanTool;
import com.example.ilink.capabilities.planning.TaskPlanningService;
import com.example.ilink.capabilities.planning.TodoService;
import com.example.ilink.capabilities.planning.TodoStore;
import com.example.ilink.capabilities.travel.AmapService;
import com.example.ilink.capabilities.travel.DidiMcpClient;
import com.example.ilink.application.workflow.travel.TaxiWorkflow;
import com.example.ilink.application.workflow.travel.TravelWorkflow;
import com.example.ilink.capabilities.visual.QrCodeService;
import com.example.ilink.capabilities.visual.VisualCardFactory;
import com.example.ilink.capabilities.visual.VisualCardRenderer;
import com.example.ilink.application.workflow.visual.VisualCardWorkflow;
import com.example.ilink.application.workflow.visual.VisualDeckSender;
import com.example.ilink.capabilities.weather.WeatherService;
import com.example.ilink.capabilities.weather.WeatherTool;
import com.example.ilink.capabilities.web.BilibiliSearchService;
import com.example.ilink.capabilities.web.NewsSearchService;
import com.example.ilink.capabilities.web.ShortLinkService;
import com.example.ilink.capabilities.web.WebSearchService;
import com.example.ilink.platform.media.MediaStore;
import com.example.ilink.platform.http.HttpClientFactory;
import com.example.ilink.platform.persistence.DefaultUserSessionStore;
import com.example.ilink.platform.persistence.MySqlStore;
import com.example.ilink.platform.persistence.UserRepository;
import com.example.ilink.platform.sdk.SdkResumeContextStore;

import java.net.http.HttpClient;
import java.net.URI;
import java.time.Duration;

/** 创建应用服务、工具、路由和外部适配器。 */
public final class ApplicationBootstrap implements AutoCloseable {

    private final MessageDispatcher messageDispatcher;
    private final WechatMessageAdapter messageAdapter;
    private final MessageSerialExecutor messageExecutor;
    private final LoginQrPage loginQrPage;
    private final SdkResumeContextStore resumeContextStore;
    private final ChatHistoryStore chatHistory;
    private final MySqlStore database;
    private final SessionManagementServer sessionManagementServer;

    private ApplicationBootstrap(MessageDispatcher messageDispatcher,
                                 WechatMessageAdapter messageAdapter,
                                 MessageSerialExecutor messageExecutor,
                                 LoginQrPage loginQrPage,
                                 SdkResumeContextStore resumeContextStore,
                                 ChatHistoryStore chatHistory,
                                 MySqlStore database,
                                 SessionManagementServer sessionManagementServer) {
        this.messageDispatcher = messageDispatcher;
        this.messageAdapter = messageAdapter;
        this.messageExecutor = messageExecutor;
        this.loginQrPage = loginQrPage;
        this.resumeContextStore = resumeContextStore;
        this.chatHistory = chatHistory;
        this.database = database;
        this.sessionManagementServer = sessionManagementServer;
    }

    public static ApplicationBootstrap create() {
        HttpClient httpClient = HttpClientFactory.create(Duration.ofSeconds(15));

        ChatHistoryStore chatHistory = new ChatHistoryStore(httpClient);
        UserSessionStore sessions = new DefaultUserSessionStore();
        AudioHistoryStore audioHistory = new AudioHistoryStore();
        DocumentSessionStore documentSessions = new DocumentSessionStore();
        PlanSessionStore planSessions = new PlanSessionStore();

        Retriever retriever = new Retriever(new EmbeddingService(httpClient), new VectorStore());
        DocumentAiService documentAiService = new DocumentAiService(httpClient, chatHistory, retriever);
        MemoryService memoryService = new MemoryService();
        MemoryExtractor memoryExtractor = new MemoryExtractor(memoryService, httpClient);
        ContextManager contextManager = new ContextManager(sessions, memoryService);
        IntentRecognizer intentRecognizer = new IntentRecognizer(httpClient, chatHistory, memoryService, sessions);
        ChatService chatService = new ChatService(httpClient, chatHistory, sessions, memoryService);

        AudioService audioService = new AudioService(httpClient);
        DocumentService documentService = new DocumentService(new VisionService(httpClient));
        ImageService imageService = new ImageService(httpClient);
        WeatherService weatherService = new WeatherService(httpClient);
        WebSearchService webSearchService = new WebSearchService(httpClient);
        BilibiliSearchService bilibiliSearchService = new BilibiliSearchService(
                webSearchService, new ShortLinkService());
        NewsSearchService newsSearchService = new NewsSearchService(httpClient);
        MediaKnowledgeService mediaKnowledgeService = new MediaKnowledgeService(
                new BangumiService(httpClient), new MusicBrainzService(httpClient),
                new LrcLibService(httpClient), webSearchService);
        QqMailService qqMailService = new QqMailService();
        TaskPlanningService planningService = new TaskPlanningService(httpClient);
        ExpenseSplitService expenseSplitService = new ExpenseSplitService(httpClient);
        ExpressService expressService = new ExpressService(httpClient);
        FoodOrderService foodOrderService = new FoodOrderService(httpClient);
        MediaStore mediaStore = new MediaStore();
        AmapService amapService = new AmapService(httpClient);
        ExpressPageService expressPageService = new ExpressPageService();
        ExpressHttpServer expressHttpServer = new ExpressHttpServer(expressPageService);

        ToolManager toolManager = new ToolManager()
                .register(new WeatherTool(weatherService))
                .register(new DrawTool(imageService))
                .register(new ImageAnalysisTool(imageService, sessions))
                .register(new ImageEditTool(imageService, sessions))
                .register(new DocumentQATool(documentAiService, documentSessions))
                .register(new DocumentGenerateTool(documentAiService, documentService))
                .register(new DocumentEditTool(documentAiService, documentService, documentSessions))
                .register(new PlanDocumentTool(documentService))
                .register(new AudioTranscribeTool(audioService, audioHistory))
                .register(new SpeechTool(audioService))
                .register(new PersonaSwitchTool(sessions))
                .register(new DateTimeTool())
                .register(new DeadlineCountdownTool())
                .register(new TaskDecompositionTool(planningService))
                .register(new TaskPlanTool(planningService))
                .register(new PlanAdjustTool(planningService, planSessions))
                .register(new PlanProgressTool(planningService, planSessions))
                .register(new CalculatorTool())
                .register(new ExpenseSplitTool(expenseSplitService))
                .register(new FoodOrderTool(foodOrderService))
                .register(new FoodDeliveryTool())
                .register(new NearbyFoodTool(amapService, new FoodPreferenceMapper(httpClient)))
                .register(new LengthTool())
                .register(new WeightTool())
                .register(new TemperatureTool())
                .register(new TimeTool())
                .register(new AreaTool())
                .register(new VolumeTool())
                .register(new SpeedTool())
                .register(new CurrencyTool())
                .register(new BaseConversionTool())
                .register(new BMITool())
                .register(new TaxTool())
                .register(new MortgageTool())
                .register(new ChineseMoneyTool())
                .register(new RelationTool())
                .register(new ExpressTool(expressService, expressPageService));

        installConfiguredMcpTools(httpClient, toolManager);

        ReplySender replySender = new ReplySender(
                audioService, mediaStore, audioHistory, toolManager, sessions, chatHistory);
        VisualCardFactory visualCardFactory = new VisualCardFactory();
        VisualDeckSender visualDeckSender = new VisualDeckSender(
                new VisualCardRenderer(new QrCodeService()), replySender::markSent, replySender::rememberText);
        CalendarService calendarService = new CalendarService(new CalendarEventStore());
        TodoService todoService = new TodoService(new TodoStore(), calendarService);
        UserRepository userRepository = new UserRepository(MySqlStore.getInstance());
        WelcomeHandler welcomeHandler = new WelcomeHandler(userRepository);
        CommandRouter commandRouter = new CommandRouter();
        NewSessionService newSessionService = new NewSessionService(sessions);
        SessionService sessionService = new SessionService(MySqlStore.getInstance(), sessions);
        CommandHandler commandHandler = new CommandHandler(
                sessionService, sessions, memoryService, todoService, planSessions, replySender);

        CalendarWorkflow calendarWorkflow = new CalendarWorkflow(
                calendarService, new CalendarSessionStore(), replySender);
        HealthDietWorkflow healthDietWorkflow = new HealthDietWorkflow(
                calendarService, new DietPlanSessionStore(), replySender, toolManager);
        NearbyFoodWorkflow nearbyFoodWorkflow = new NearbyFoodWorkflow(sessions, toolManager, replySender);
        FoodOrderWorkflow foodOrderWorkflow = new FoodOrderWorkflow(
                sessions, amapService, foodOrderService, replySender);
        TravelWorkflow travelWorkflow = new TravelWorkflow(amapService, calendarService, replySender);
        TaxiWorkflow taxiWorkflow = new TaxiWorkflow(new DidiMcpClient(), replySender);
        PlanWorkflow planWorkflow = new PlanWorkflow(
                toolManager, planSessions, chatHistory, replySender, documentService, calendarService);
        VisualCardWorkflow visualCardWorkflow = new VisualCardWorkflow(
                visualDeckSender, visualCardFactory, planSessions, calendarService, todoService,
                toolManager, foodOrderService, qqMailService, newsSearchService,
                mediaKnowledgeService, bilibiliSearchService, amapService);

        UserRequestHandler requestHandler = new UserRequestHandler(
                chatHistory, sessions, documentSessions,
                intentRecognizer, chatService, weatherService,
                mediaStore, replySender, toolManager, new RoutePlanReviewer(), contextManager, planWorkflow,
                new CalculatorService(httpClient, toolManager), calendarWorkflow,
                healthDietWorkflow, travelWorkflow, nearbyFoodWorkflow, foodOrderWorkflow,
                taxiWorkflow, memoryService, todoService,
                webSearchService, newsSearchService, bilibiliSearchService, mediaKnowledgeService,
                qqMailService, visualCardWorkflow);
        MessageProcessor messageProcessor = new MessageProcessor(
                chatHistory, sessions, audioHistory, documentSessions,
                audioService, imageService, documentService, memoryService,
                mediaStore, replySender, new CapabilityDispatcher(requestHandler),
                commandRouter, commandHandler, memoryExtractor);

        LoginBriefingService loginBriefingService = new LoginBriefingService(
                weatherService, calendarService, todoService, planSessions, sessions,
                new HolidayService(), memoryService, qqMailService, newsSearchService, webSearchService);
        DailyDashboardService dashboardService = new DailyDashboardService(
                todoService, calendarService, planSessions, weatherService, sessions, memoryService);

        SessionManagementServer sessionManagementServer = new SessionManagementServer(
                sessionService, sessions, MySqlStore.getInstance());
        MessageDispatcher dispatcher = new MessageDispatcher(
                messageProcessor, replySender, chatService, calendarService,
                visualDeckSender, loginBriefingService, welcomeHandler,
                new DailyDashboardServer(dashboardService), sessionManagementServer,
                expressHttpServer, expressPageService);
        sessionManagementServer.start();
        return new ApplicationBootstrap(
                dispatcher, new WechatMessageAdapter(), new MessageSerialExecutor(),
                new LoginQrPage(), new SdkResumeContextStore(), chatHistory,
                MySqlStore.getInstance(), sessionManagementServer);
    }

    public MessageDispatcher messageDispatcher() {
        return messageDispatcher;
    }

    private static void installConfiguredMcpTools(HttpClient httpClient, ToolManager toolManager) {
        if (Config.MCP_SERVER_URL.isBlank()) return;
        try {
            McpServerRegistry servers = new McpServerRegistry().register("default",
                    new HttpMcpClient(httpClient, URI.create(Config.MCP_SERVER_URL), Config.MCP_SERVER_AUTH));
            servers.installTools(toolManager);
        } catch (Exception error) {
            System.err.println("[MCP] 工具发现失败，继续使用本地能力：" + error.getMessage());
        }
    }

    public WechatMessageAdapter messageAdapter() {
        return messageAdapter;
    }

    public MessageSerialExecutor messageExecutor() {
        return messageExecutor;
    }

    public LoginQrPage loginQrPage() {
        return loginQrPage;
    }

    public SdkResumeContextStore resumeContextStore() {
        return resumeContextStore;
    }

    @Override
    public void close() {
        sessionManagementServer.close();
        messageExecutor.close();
        messageDispatcher.close();
        loginQrPage.cleanup();
        chatHistory.close();
        database.close();
    }
}
