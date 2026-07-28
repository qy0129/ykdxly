package com.example.ilink.adapter.inbound.wechat;

import com.example.ilink.adapter.inbound.http.DailyDashboardServer;
import com.example.ilink.adapter.inbound.http.ExpressHttpServer;
import com.example.ilink.adapter.outbound.wechat.WechatReplyChannel;
import com.example.ilink.application.briefing.LoginBriefingService;
import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.application.messaging.IncomingMessage;
import com.example.ilink.application.messaging.MessageProcessor;
import com.example.ilink.application.messaging.ReplySender;
import com.example.ilink.application.messaging.UserRequestHandler;
import com.example.ilink.application.welcome.WelcomeHandler;
import com.example.ilink.application.workflow.visual.VisualDeckSender;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.calendar.CalendarEvent;
import com.example.ilink.capabilities.calendar.CalendarService;
import com.example.ilink.capabilities.calendar.ReminderDelivery;
import com.example.ilink.capabilities.calendar.ReminderTextFormatter;
import com.example.ilink.capabilities.chat.ChatService;
import com.example.ilink.capabilities.express.ExpressPageService;
import com.example.ilink.platform.network.CloudflareTunnel;
import com.github.wechat.ilink.sdk.ILinkClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信消息分发器。
 *
 * <p>将 SDK 收到的文本、图片、文件、语音等消息转换为统一的处理流程，
 * 同时负责下载媒体、保存会话状态，并把文本请求交给
 * {@link UserRequestHandler}。</p>
 */
public final class MessageDispatcher implements AutoCloseable {

    private final MessageProcessor messageProcessor;
    private final ReplySender replySender;
    private final ChatService chatService;
    private final CalendarService calendarService;
    private final VisualDeckSender visualDeckSender;
    private final LoginBriefingService loginBriefingService;
    private final WelcomeHandler welcomeHandler;
    private final DailyDashboardServer dailyDashboardServer;
    private final ExpressHttpServer expressHttpServer;
    private final ExpressPageService expressPageService;
    private final ScheduledExecutorService progressScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService reminderScheduler = Executors.newScheduledThreadPool(1);
    private final ScheduledExecutorService briefingScheduler = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean briefingInProgress = new AtomicBoolean();
    private volatile boolean briefingSent;
    private volatile String activeUserId = "";
    private CloudflareTunnel expressTunnel;
    private volatile ILinkClient activeClient;

    /** 创建分发器时就准备提醒扫描；真正发消息前必须等待登录客户端就绪。 */
    public MessageDispatcher(MessageProcessor messageProcessor, ReplySender replySender,
                             ChatService chatService, CalendarService calendarService,
                             VisualDeckSender visualDeckSender, LoginBriefingService loginBriefingService,
                             WelcomeHandler welcomeHandler,
                             DailyDashboardServer dailyDashboardServer, ExpressHttpServer expressHttpServer,
                             ExpressPageService expressPageService) {
        this.messageProcessor = messageProcessor;
        this.replySender = replySender;
        this.chatService = chatService;
        this.calendarService = calendarService;
        this.visualDeckSender = visualDeckSender;
        this.loginBriefingService = loginBriefingService;
        this.welcomeHandler = welcomeHandler;
        this.dailyDashboardServer = dailyDashboardServer;
        this.expressHttpServer = expressHttpServer;
        this.expressPageService = expressPageService;
        dailyDashboardServer.start();
        expressHttpServer.start();
        startExpressTunnel();
        reminderScheduler.scheduleAtFixedRate(this::sendDueReminders, 1, 1, TimeUnit.SECONDS);
    }

    private void startExpressTunnel() {
        if (!Config.EXPRESS_BASE_URL.isBlank() || !Config.EXPRESS_TUNNEL_ENABLED) return;
        expressTunnel = new CloudflareTunnel(Config.EXPRESS_TUNNEL_COMMAND,
                expressPageService.actualPort, Config.EXPRESS_TUNNEL_TIMEOUT);
        String publicUrl = expressTunnel.start();
        if (publicUrl.isBlank()) {
            expressTunnel.close();
            expressTunnel = null;
            System.err.println("[快递H5] 公网隧道启动失败，暂时使用本机地址");
            return;
        }
        expressPageService.useBaseUrl(publicUrl);
        System.out.println("[快递H5] 公网地址：" + publicUrl);
    }

    /** 登录成功后注入长连接，供没有新入站消息时的主动提醒使用。 */
    public void onClientReady(ILinkClient client) {
        this.activeClient = client;
        briefingSent = false;
        briefingInProgress.set(false);
        if (Config.LOGIN_BRIEFING_ENABLED) {
            System.out.println("[登录简报] 登录触发，等待会话上下文就绪");
            briefingScheduler.schedule(() -> sendLoginBriefing(client), 1, TimeUnit.SECONDS);
        }
    }

    /** 接收一条 SDK 消息，并异步提交给内部处理流程。 */
    public void handleMessage(ILinkClient client, IncomingMessage message) {
        activeClient = client;
        String userId = message.principalId();
        try {
            welcomeHandler.handleFirstLogin(new WechatReplyChannel(client), userId);
        } catch (Exception e) {
            System.err.println("[欢迎] 处理失败: " + e.getMessage());
        }
        AgentContext context = AgentContext.wechat(userId, new WechatReplyChannel(client));
        activeUserId = userId;
        dailyDashboardServer.useUser(userId);
        if (Config.LOGIN_BRIEFING_ENABLED) {
            briefingScheduler.execute(() -> sendLoginBriefing(client));
        }
        long startedAtMillis = System.currentTimeMillis();
        boolean voiceOnly = replySender.isVoiceOnly();
        ScheduledFuture<?> progressTask = progressScheduler.schedule(() -> {
            try {
                if (!voiceOnly && !replySender.hasSentReplySince(startedAtMillis)) {
                    client.sendText(userId, "正在回复中，请稍等......");
                }
            } catch (Exception e) {
                System.err.println("发送处理中提示失败: " + e.getMessage());
            }
        }, 12, TimeUnit.SECONDS);

        try {
            messageProcessor.process(context, message);
        } finally {
            progressTask.cancel(false);
        }
    }

    /** 关闭进度调度器，释放分发器持有的后台资源。 */
    @Override
    public void close() {
        progressScheduler.shutdownNow();
        reminderScheduler.shutdownNow();
        briefingScheduler.shutdownNow();
        dailyDashboardServer.close();
        expressHttpServer.stop();
        if (expressTunnel != null) expressTunnel.close();
    }

    /** 扫描并发送当前会话的到期提醒。 */
    private void sendDueReminders() {
        ILinkClient client = activeClient;
        if (client == null || !client.isLoggedIn()) return;
        String userId = currentUserId(client);
        if (userId.isBlank()) return;
        for (ReminderDelivery delivery : calendarService.claimDueReminders(LocalDateTime.now(), Set.of(userId))) {
            CalendarEvent event = calendarService.getEvent(delivery.eventId());
            if (event == null) {
                calendarService.markReminderFailed(delivery, LocalDateTime.now(), "日历事件不存在");
                continue;
            }
            try {
                replySender.sendReply(new WechatReplyChannel(client), userId, ReminderTextFormatter.format(event));
                calendarService.markReminderSent(delivery, LocalDateTime.now());
            } catch (Exception e) {
                System.err.println("[日历提醒] 发送失败: " + e.getMessage());
                calendarService.markReminderFailed(delivery, LocalDateTime.now(), e.getMessage());
            }
        }
    }

    /** 每次机器人登录后，为当前会话发送简报并补发逾期提醒。 */
    private void sendLoginBriefing(ILinkClient client) {
        if (client == null || !client.isLoggedIn()) {
            System.err.println("[登录简报] 跳过：客户端尚未登录");
            return;
        }
        String userId = currentUserId(client);
        if (!userId.isBlank()) sendCurrentLoginBriefing(client, userId);
    }

    /** 用户具备上下文时发送一次简报；失败则允许后续消息再次触发。 */
    private void sendCurrentLoginBriefing(ILinkClient client, String userId) {
        if (client == null || !client.isLoggedIn()) return;
        if (!hasSendContext(client, userId)) {
            System.out.println("[登录简报] 跳过：用户缺少可发送会话上下文 user=" + userId);
            return;
        }
        if (briefingSent || !briefingInProgress.compareAndSet(false, true)) return;

        try {
            List<ReminderDelivery> deliveries = calendarService.claimOverdueRemindersForUser(
                    userId, LocalDateTime.now());
            try {
                System.out.println("[登录简报] 开始构建 user=" + userId);
                String draft = loginBriefingService.build(userId, List.of());
                String dashboardUrl = dailyDashboardServer.urlFor(userId);
                String message = chatService.polishBriefing(userId, draft);
                String textFallback = dashboardUrl.isBlank() ? message
                        : message + "\n\n你的七日计划页：\n" + dashboardUrl;
                visualDeckSender.sendText(new WechatReplyChannel(client), userId, textFallback);
                briefingSent = true;
                System.out.println("[登录简报] 发送成功 user=" + userId);
            } catch (Exception e) {
                System.err.println("[登录简报] 发送失败 user=" + userId + ": " + e.getMessage());
            }
            for (ReminderDelivery delivery : deliveries) {
                CalendarEvent event = calendarService.getEvent(delivery.eventId());
                if (event == null) {
                    calendarService.markReminderFailed(delivery, LocalDateTime.now(), "日历事件不存在");
                    continue;
                }
                try {
                    replySender.sendReply(new WechatReplyChannel(client), userId, ReminderTextFormatter.format(event));
                    calendarService.markReminderSent(delivery, LocalDateTime.now());
                } catch (Exception e) {
                    System.err.println("[离线提醒] 补发失败 user=" + userId + ": " + e.getMessage());
                    calendarService.markReminderFailed(delivery, LocalDateTime.now(), e.getMessage());
                }
            }
        } finally {
            briefingInProgress.set(false);
        }
    }

    private boolean hasSendContext(ILinkClient client, String userId) {
        var resume = client.exportResumeContext();
        if (resume == null) return false;
        var context = resume.getConversationContextMap().get(userId);
        return context != null && context.hasContextToken();
    }

    private String currentUserId(ILinkClient client) {
        String current = activeUserId;
        if (!current.isBlank() && hasSendContext(client, current)) return current;
        var resume = client.exportResumeContext();
        if (resume == null) return "";
        String found = "";
        for (var entry : resume.getConversationContextMap().entrySet()) {
            if (entry.getValue() == null || !entry.getValue().hasContextToken()) continue;
            if (!found.isBlank()) return "";
            found = entry.getKey();
        }
        if (!found.isBlank()) {
            activeUserId = found;
            dailyDashboardServer.useUser(found);
        }
        return found;
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
