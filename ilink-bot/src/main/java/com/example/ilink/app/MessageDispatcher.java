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
import com.example.ilink.feature.finance.ExpenseSplitService;
import com.example.ilink.feature.image.ImageService;
import com.example.ilink.feature.persona.Personas;
import com.example.ilink.feature.planning.TaskPlanningService;
import com.example.ilink.feature.calculator.CalculatorService;
import com.example.ilink.feature.weather.WeatherService;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.feature.travel.AmapService;
import com.example.ilink.tools.audio.AudioTranscribeTool;
import com.example.ilink.tools.audio.SpeechTool;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.finance.ExpenseSplitTool;
import com.example.ilink.tools.food.FoodDeliveryTool;
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
import com.example.ilink.storage.CalendarEventStore;
import com.example.ilink.model.CalendarEvent;
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
    private final IntentRecognizer intentRecognizer = new IntentRecognizer(httpClient);
    private final ChatService chatService = new ChatService(httpClient, chatHistory, sessions);
    private final DocumentAiService documentAiService = new DocumentAiService(httpClient, chatHistory);
    private final AudioService audioService = new AudioService(httpClient);
    private final DocumentService documentService = new DocumentService();
    private final ImageService imageService = new ImageService(httpClient);
    private final WeatherService weatherService = new WeatherService(httpClient);
    private final TaskPlanningService planningService = new TaskPlanningService(httpClient);
    private final ExpenseSplitService expenseSplitService = new ExpenseSplitService(httpClient);
    private final MediaStore mediaStore = new MediaStore();
    private final AmapService amapService = new AmapService(httpClient);
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
            .register(new RelationTool());
    private final CalculatorService calculatorService = new CalculatorService(httpClient, toolManager);
    private final CalculatorTextRouter calculatorTextRouter = new CalculatorTextRouter(toolManager);
    private final Set<String> voiceReplyUsers = ConcurrentHashMap.newKeySet();
    private final ReplySender replySender = new ReplySender(
            audioService, mediaStore, audioHistory, toolManager);
    private final CalendarService calendarService = new CalendarService(new CalendarEventStore());
    private final CalendarWorkflow calendarWorkflow = new CalendarWorkflow(
            calendarService, new CalendarSessionStore(), replySender);
    private final HealthDietWorkflow healthDietWorkflow = new HealthDietWorkflow(
            calendarService, new DietPlanSessionStore(), replySender, toolManager);
    private final NearbyFoodWorkflow nearbyFoodWorkflow = new NearbyFoodWorkflow(
            sessions, toolManager, replySender);
    private final TravelWorkflow travelWorkflow = new TravelWorkflow(
            amapService, calendarService, replySender);
    private final PlanWorkflow planWorkflow = new PlanWorkflow(
            toolManager, planSessions, chatHistory, replySender, documentService, calendarService);
    private final UserRequestHandler requestHandler = new UserRequestHandler(
            chatHistory, sessions, documentSessions,
            intentRecognizer, chatService, weatherService,
            mediaStore, replySender, toolManager, planWorkflow, calculatorService, calendarWorkflow,
            healthDietWorkflow, travelWorkflow, nearbyFoodWorkflow);
    private final ScheduledExecutorService progressScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService reminderScheduler = Executors.newScheduledThreadPool(1);
    private volatile ILinkClient activeClient;

    /** 创建分发器时就准备提醒扫描；真正发消息前必须等待登录客户端就绪。 */
    public MessageDispatcher() {
        reminderScheduler.scheduleAtFixedRate(this::sendDueReminders, 10, 60, TimeUnit.SECONDS);
    }

    /** 登录成功后注入长连接，供没有新入站消息时的主动提醒使用。 */
    public void onClientReady(ILinkClient client) {
        this.activeClient = client;
    }

    /** 接收一条 SDK 消息，并异步提交给内部处理流程。 */
    public void handleMessage(ILinkClient client, WeixinMessage message) {
        activeClient = client;
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
                if (calculatorTextRouter.isCalculatorCommand(text)) {
                    replySender.sendReply(client, userId, calculatorTextRouter.handle(userId, text));
                    return;
                }
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
        reminderScheduler.shutdownNow();
    }

    /** 扫描并发送到期提醒，单条发送失败不会影响后续用户的提醒。 */
    private void sendDueReminders() {
        ILinkClient client = activeClient;
        if (client == null || !client.isLoggedIn()) return;
        for (CalendarEvent event : calendarService.claimDueEvents(LocalDateTime.now())) {
            try {
                String repeat = "none".equals(event.recurrence()) ? "" : "这是一项" + recurrenceName(event.recurrence()) + "提醒。";
                replySender.sendReply(client, event.userId(), "提醒你：" + event.title() + "\n" + repeat);
            } catch (Exception e) {
                System.err.println("[日历提醒] 发送失败: " + e.getMessage());
            }
        }
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
