package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.AudioHistoryStore;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.conversation.CalendarSessionStore;
import com.example.ilink.conversation.DietPlanSessionStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.audio.AudioService;
import com.example.ilink.feature.chat.ChatService;
import com.example.ilink.feature.document.DocumentAiService;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.express.ExpressService;
import com.example.ilink.feature.express.ExpressPageService;
import com.example.ilink.feature.finance.ExpenseSplitService;
import com.example.ilink.feature.food.FoodOrderService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.image.GeneratedImage;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.feature.memory.MemoryService;
import com.example.ilink.feature.mail.QqMailService;
import com.example.ilink.feature.media.BangumiService;
import com.example.ilink.feature.media.LrcLibService;
import com.example.ilink.feature.media.MediaKnowledgeService;
import com.example.ilink.feature.media.MusicBrainzService;
import com.example.ilink.feature.planning.TaskPlanningService;
import com.example.ilink.feature.planning.TodoService;
import com.example.ilink.feature.calculator.CalculatorService;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.feature.web.NewsSearchService;
import com.example.ilink.feature.web.BilibiliSearchService;
import com.example.ilink.feature.web.ShortLinkService;
import com.example.ilink.feature.web.WebSearchService;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.calendar.HolidayService;
import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.feature.visual.QrCodeService;
import com.example.ilink.feature.visual.VisualCardFactory;
import com.example.ilink.feature.visual.VisualCardRenderer;
import com.example.ilink.feature.visual.VisualDeckSender;
import com.example.ilink.tools.audio.AudioTranscribeTool;
import com.example.ilink.tools.audio.SpeechTool;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.finance.ExpenseSplitTool;
import com.example.ilink.tools.express.ExpressTool;
import com.example.ilink.tools.food.FoodDeliveryTool;
import com.example.ilink.tools.food.FoodOrderTool;
import com.example.ilink.tools.food.NearbyFoodTool;
import com.example.ilink.tools.document.DocumentEditTool;
import com.example.ilink.tools.document.DocumentGenerateTool;
import com.example.ilink.tools.document.DocumentQATool;
import com.example.ilink.tools.document.PlanDocumentTool;
import com.example.ilink.tools.image.DrawTool;
import com.example.ilink.tools.image.ImageAnalysisTool;
import com.example.ilink.tools.image.ImageEditTool;
import com.example.ilink.tools.calculator.AreaTool;
import com.example.ilink.tools.calculator.BaseConversionTool;
import com.example.ilink.tools.calculator.BMITool;
import com.example.ilink.tools.calculator.CalculatorTextRouter;
import com.example.ilink.tools.calculator.ChineseMoneyTool;
import com.example.ilink.tools.calculator.CurrencyTool;
import com.example.ilink.tools.calculator.LengthTool;
import com.example.ilink.tools.calculator.MortgageTool;
import com.example.ilink.tools.calculator.RelationTool;
import com.example.ilink.tools.calculator.SpeedTool;
import com.example.ilink.tools.calculator.TaxTool;
import com.example.ilink.tools.calculator.TemperatureTool;
import com.example.ilink.tools.calculator.TimeTool;
import com.example.ilink.tools.calculator.VolumeTool;
import com.example.ilink.tools.calculator.WeightTool;
import com.example.ilink.tools.math.CalculatorTool;
import com.example.ilink.tools.persona.PersonaSwitchTool;
import com.example.ilink.tools.planning.DateTimeTool;
import com.example.ilink.tools.planning.DeadlineCountdownTool;
import com.example.ilink.tools.planning.PlanAdjustTool;
import com.example.ilink.tools.planning.PlanProgressTool;
import com.example.ilink.tools.planning.TaskDecompositionTool;
import com.example.ilink.tools.planning.TaskPlanTool;
import com.example.ilink.tools.weather.WeatherTool;
import com.example.ilink.model.AudioRecord;
import com.example.ilink.model.AudioSource;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.routing.IntentRecognizer;
import com.example.ilink.storage.MediaStore;
import com.example.ilink.storage.MySqlStore;
import com.example.ilink.storage.TodoStore;
import com.example.ilink.storage.CalendarEventStore;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.model.ReminderDelivery;
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
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.time.LocalDateTime;

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
    private final PlanSessionStore planSessions = new PlanSessionStore();
    private final MemoryService memoryService = new MemoryService();
    private final IntentRecognizer intentRecognizer = new IntentRecognizer(
            httpClient, chatHistory, memoryService, sessions);
    private final ChatService chatService = new ChatService(httpClient, chatHistory, sessions, memoryService);
    private final DocumentAiService documentAiService = new DocumentAiService(httpClient, chatHistory);
    private final AudioService audioService = new AudioService(httpClient);
    private final DocumentService documentService = new DocumentService();
    private final ImageService imageService = new ImageService(httpClient);
    private final WeatherService weatherService = new WeatherService(httpClient);
    private final WebSearchService webSearchService = new WebSearchService(httpClient);
    private final ShortLinkService shortLinkService = new ShortLinkService();
    private final BilibiliSearchService bilibiliSearchService = new BilibiliSearchService(
            webSearchService, shortLinkService);
    private final NewsSearchService newsSearchService = new NewsSearchService(httpClient);
    private final MediaKnowledgeService mediaKnowledgeService = new MediaKnowledgeService(
            new BangumiService(httpClient), new MusicBrainzService(httpClient),
            new LrcLibService(httpClient), webSearchService);
    private final QqMailService qqMailService = new QqMailService();
    private final TaskPlanningService planningService = new TaskPlanningService(httpClient);
    private final ExpenseSplitService expenseSplitService = new ExpenseSplitService(httpClient);
    private final ExpressService expressService = new ExpressService(httpClient);
    private final FoodOrderService foodOrderService = new FoodOrderService(httpClient);
    private final MediaStore mediaStore = new MediaStore();
    private final AmapService amapService = new AmapService(httpClient);
    private final ExpressPageService expressPageService = new ExpressPageService(amapService);
    private final ToolManager toolManager = new ToolManager()
            .register(new WeatherTool(weatherService))
            .register(new DrawTool(imageService))
            .register(new ImageAnalysisTool(imageService, sessions))
            .register(new ImageEditTool(imageService, sessions))
            .register(new DocumentQATool(documentAiService, documentSessions))
            .register(new DocumentGenerateTool(documentAiService, documentService, documentSessions))
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
            .register(new NearbyFoodTool(amapService))
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

    private final CalculatorService calculatorService = new CalculatorService(httpClient, toolManager);
    private final CalculatorTextRouter calculatorTextRouter = new CalculatorTextRouter(toolManager);
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();
    private final ReplySender replySender = new ReplySender(
            audioService, mediaStore, audioHistory, toolManager, sessions, chatHistory);
    private final VisualCardFactory visualCardFactory = new VisualCardFactory();
    private final VisualDeckSender visualDeckSender = new VisualDeckSender(
            new VisualCardRenderer(new QrCodeService()), replySender::markSent, replySender::rememberText);
    private final CalendarService calendarService = new CalendarService(new CalendarEventStore());
    private final TodoService todoService = new TodoService(new TodoStore(), calendarService);
    private final CalendarWorkflow calendarWorkflow = new CalendarWorkflow(
            calendarService, new CalendarSessionStore(), replySender);
    private final HealthDietWorkflow healthDietWorkflow = new HealthDietWorkflow(
            calendarService, new DietPlanSessionStore(), replySender, toolManager);
    private final NearbyFoodWorkflow nearbyFoodWorkflow = new NearbyFoodWorkflow(
            sessions, toolManager, replySender);
    private final FoodOrderWorkflow foodOrderWorkflow = new FoodOrderWorkflow(
            sessions, amapService, foodOrderService, replySender);
    private final TravelWorkflow travelWorkflow = new TravelWorkflow(
            amapService, calendarService, replySender);
    private final PlanWorkflow planWorkflow = new PlanWorkflow(
            toolManager, planSessions, chatHistory, replySender, documentService, calendarService);
    private final VisualCardWorkflow visualCardWorkflow = new VisualCardWorkflow(
            visualDeckSender, visualCardFactory, planSessions, calendarService, todoService,
            toolManager, foodOrderService, qqMailService, newsSearchService,
            mediaKnowledgeService, bilibiliSearchService, amapService);
    private final UserRequestHandler requestHandler = new UserRequestHandler(
            chatHistory, sessions, documentSessions,
            intentRecognizer, chatService, weatherService,
            mediaStore, replySender, toolManager, planWorkflow, calculatorService, calendarWorkflow,
            healthDietWorkflow, travelWorkflow, nearbyFoodWorkflow, foodOrderWorkflow,
            memoryService, todoService,
            webSearchService, newsSearchService, bilibiliSearchService, mediaKnowledgeService,
            qqMailService, visualCardWorkflow);
    private final ScheduledExecutorService progressScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService reminderScheduler = Executors.newScheduledThreadPool(1);
    private final Set<String> knownUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> briefedUsers = ConcurrentHashMap.newKeySet();
    private final LoginBriefingService loginBriefingService = new LoginBriefingService(
            weatherService, calendarService, todoService, planSessions, sessions,
            new HolidayService(), memoryService, qqMailService, newsSearchService, webSearchService);
    private final DailyDashboardServer dailyDashboardServer;
    private volatile ILinkClient activeClient;

    /** 创建分发器时就准备提醒扫描；真正发消息前必须等待登录客户端就绪。 */
    public MessageDispatcher() {
        DailyDashboardService dashboardService = new DailyDashboardService(
                todoService, calendarService, planSessions, weatherService, sessions, memoryService);
        dailyDashboardServer = new DailyDashboardServer(dashboardService, expressPageService);
        dailyDashboardServer.start();
        reminderScheduler.scheduleAtFixedRate(this::sendDueReminders, 1, 1, TimeUnit.SECONDS);
    }

    /** 登录成功后注入长连接，供没有新入站消息时的主动提醒使用。 */
    public void onClientReady(ILinkClient client) {
        this.activeClient = client;
        briefedUsers.clear();
        if (Config.LOGIN_BRIEFING_ENABLED) {
            reminderScheduler.execute(() -> sendLoginBriefings(client));
        }
    }

    /** 接收一条 SDK 消息，并异步提交给内部处理流程。 */
    public void handleMessage(ILinkClient client, WeixinMessage message) {
        activeClient = client;
        String userId = message.getFrom_user_id();
        dailyDashboardServer.useUser(userId);
        knownUsers.add(userId);
        if (Config.LOGIN_BRIEFING_ENABLED) {
            reminderScheduler.execute(() -> sendLoginBriefingForUser(client, userId));
        }
        long startedAtMillis = System.currentTimeMillis();
        boolean voiceOnly = voiceReplyUsers.contains(userId)
                || "voice".equalsIgnoreCase(Config.REPLY_MODE);
        ScheduledFuture<?> progressTask = progressScheduler.schedule(() -> {
            try {
                if (!voiceOnly && !replySender.hasSentReplySince(userId, startedAtMillis)) {
                    client.sendText(userId, "正在回复中，请稍等......");
                }
            } catch (Exception e) {
                System.err.println("发送处理中提示失败: " + e.getMessage());
            }
        }, 12, TimeUnit.SECONDS);

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
            knownUsers.add(userId);
            client.startTyping(userId);
            List<com.github.wechat.ilink.sdk.core.model.MessageItem> items = message.getItem_list();
            if (items == null || items.isEmpty()) return;

            com.github.wechat.ilink.sdk.core.model.MessageItem first = items.get(0);
            com.github.wechat.ilink.sdk.core.model.MessageItem textMessage = items.stream()
                    .filter(item -> item.getText_item() != null)
                    .findFirst().orElse(null);
            com.github.wechat.ilink.sdk.core.model.MessageItem imageMessage = items.stream()
                    .filter(item -> item.getImage_item() != null)
                    .findFirst().orElse(null);

            // 图片优先落盘，再处理同一消息携带的文字要求，确保视觉工具能取得当前图片。
            if (imageMessage != null) {
                byte[] image = client.downloadImageFromMessageItem(imageMessage);
                if (image == null || image.length == 0) {
                    replySender.sendReply(client, userId, "图片下载失败");
                    return;
                }

                GeneratedImage receivedImage = GeneratedImage.from(image, null);
                Path saved = mediaStore.save(userId, "image", image, receivedImage.extension());
                documentSessions.clear(userId);
                sessions.setLastImage(userId, saved.toString());
                sessions.setPendingImage(userId, saved.toString());
                String caption = textMessage == null ? "" : textMessage.getText_item().getText();
                if (caption != null && !caption.isBlank()) {
                    System.out.println("[" + userId + "] [图片] " + caption);
                    chatHistory.addUserMessage(userId, caption);
                    requestHandler.handle(client, userId, caption);
                } else {
                    replySender.sendReply(client, userId,
                            "我已经收到这张图片。你想让我做什么：分析内容、解答题目，还是修改图片？");
                }
                return;
            }

            if (textMessage != null) {
                String text = textMessage.getText_item().getText();
                System.out.println("[" + userId + "] " + text);
                chatHistory.addUserMessage(userId, text);
                if (calculatorTextRouter.isCalculatorCommand(text)) {
                    replySender.sendReply(client, userId, calculatorTextRouter.handle(userId, text));
                    return;
                }
                requestHandler.handle(client, userId, text);
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
                chatHistory.addUserMessage(userId, text);
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
                    sessions.clearPendingImage(userId);
                    sessions.clearPendingDraw(userId);
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
            replySender.sendReply(client, message.getFrom_user_id(), "网络波动了，请再发一次～");
            } catch (Exception ignored) {}
        }
    }

    /** 关闭进度调度器，释放分发器持有的后台资源。 */
    @Override
    public void close() {
        progressScheduler.shutdownNow();
        reminderScheduler.shutdownNow();
        dailyDashboardServer.close();
    }

    /** 扫描并发送到期提醒，单条发送失败不会影响后续用户的提醒。 */
    private void sendDueReminders() {
        ILinkClient client = activeClient;
        if (client == null || !client.isLoggedIn()) return;
        Set<String> sendableUsers = sendableUsers(client);
        if (sendableUsers.isEmpty()) return;
        for (ReminderDelivery delivery : calendarService.claimDueReminders(LocalDateTime.now(), sendableUsers)) {
            CalendarEvent event = calendarService.getEvent(delivery.eventId());
            if (event == null) {
                calendarService.markReminderFailed(delivery, LocalDateTime.now(), "日历事件不存在");
                continue;
            }
            try {
                String repeat = "none".equals(event.recurrence()) ? ""
                        : "\n这是你设置的" + recurrenceName(event.recurrence()) + "提醒，我会继续替你记着。";
                replySender.sendReply(client, event.userId(),
                        "时间到了，来轻轻提醒你一下：" + event.title() + "。"
                                + repeat + "\n愿你接下来的安排顺顺利利。" );
                calendarService.markReminderSent(delivery, LocalDateTime.now());
            } catch (Exception e) {
                System.err.println("[日历提醒] 发送失败: " + e.getMessage());
                calendarService.markReminderFailed(delivery, LocalDateTime.now(), e.getMessage());
            }
        }
    }

    /** 每次机器人登录后，为所有已知用户发送简报并补发逾期提醒。 */
    private void sendLoginBriefings(ILinkClient client) {
        if (client == null || !client.isLoggedIn()) return;
        Set<String> users = new HashSet<>(MySqlStore.getInstance().loadKnownUserIds());
        users.addAll(knownUsers);
        for (String userId : users) {
            sendLoginBriefingForUser(client, userId);
        }
    }

    /** 用户具备上下文时发送一次简报；失败则允许后续消息再次触发。 */
    private void sendLoginBriefingForUser(ILinkClient client, String userId) {
        if (client == null || !client.isLoggedIn() || !hasSendContext(client, userId)
                || !briefedUsers.add(userId)) return;
        List<ReminderDelivery> deliveries = calendarService.claimOverdueRemindersForUser(
                userId, LocalDateTime.now());
        try {
            String draft = loginBriefingService.build(userId, deliveries);
            String dashboardUrl = dailyDashboardServer.urlFor(userId);
            String message = chatService.polishBriefing(userId, draft);
            String textFallback = dashboardUrl.isBlank() ? message
                    : message + "\n\n你的七日计划页：\n" + dashboardUrl;
            visualDeckSender.sendText(client, userId, textFallback);
            for (ReminderDelivery delivery : deliveries) {
                calendarService.markReminderSent(delivery, LocalDateTime.now());
            }
        } catch (Exception e) {
            briefedUsers.remove(userId);
            System.err.println("[登录简报] 发送失败 user=" + userId + ": " + e.getMessage());
            for (ReminderDelivery delivery : deliveries) {
                calendarService.markReminderFailed(delivery, LocalDateTime.now(), e.getMessage());
            }
        }
    }

    private boolean hasSendContext(ILinkClient client, String userId) {
        var resume = client.exportResumeContext();
        if (resume == null) return false;
        var context = resume.getConversationContextMap().get(userId);
        return context != null && context.hasContextToken();
    }

    private Set<String> sendableUsers(ILinkClient client) {
        var resume = client.exportResumeContext();
        if (resume == null) return Set.of();
        Set<String> users = new HashSet<>();
        resume.getConversationContextMap().forEach((userId, context) -> {
            if (context != null && context.hasContextToken()) users.add(userId);
        });
        return users;
    }

    private String recurrenceName(String recurrence) {
        return switch (recurrence) {
            case "daily" -> "每日";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            case "yearly" -> "每年";
            default -> "周期";
        };
    }
}
