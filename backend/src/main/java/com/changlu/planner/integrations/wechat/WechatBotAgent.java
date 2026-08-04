package com.changlu.planner.integrations.wechat;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.changlu.planner.shared.database.Database;
import com.changlu.planner.features.reminder.ReminderService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;

import java.awt.Desktop;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 长路计划的微信入口：扫码、免扫码恢复、收发文本和计划提醒。 */
public final class WechatBotAgent implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(WechatBotAgent.class);
  private static final long LOGIN_STATUS_INTERVAL_MS = 2000L;
  private final AtomicBoolean running = new AtomicBoolean(true);
  private final Database database;
  private final ResumeContextStore resumeStore;
  private final ReminderService reminders;
  private final QrLoginPage qrPage = new QrLoginPage();
  private final PlannerWechatClient planner = new PlannerWechatClient();
  private final ExecutorService briefingExecutor = Executors.newSingleThreadExecutor(runnable -> {
    Thread thread = new Thread(runnable, "wechat-briefing-agent");
    thread.setDaemon(true);
    return thread;
  });
  private final AtomicBoolean briefingInProgress = new AtomicBoolean();
  private final ScheduledExecutorService reminderExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
    Thread thread = new Thread(runnable, "wechat-reminder-dispatcher");
    thread.setDaemon(true);
    return thread;
  });
  private volatile ILinkClient client;
  private volatile ScheduledFuture<?> reminderTask;
  private volatile String webUrl;
  private volatile String pendingGreetingUserId = "";
  private Thread worker;

  public WechatBotAgent(Database database) {
    this.database = database;
    this.resumeStore = new ResumeContextStore(database);
    this.reminders = new ReminderService(database);
  }

  public void start() {
    webUrl = configuredWebUrl();
    LOG.info("[微信Bot] 启动，网页地址={}", webUrl);
    worker = new Thread(this::runLoop, "wechat-bot-agent");
    worker.setDaemon(true);
    worker.start();
  }

  private void runLoop() {
    while (running.get()) {
      try {
        ResumeContext saved = resumeStore.load();
        ILinkClient current = createClient(saved);
        client = current;
        boolean loggedIn = saved != null && current.isLoggedIn();
        if (loggedIn) {
          try { current.getUpdates(); resumeStore.save(current.exportResumeContext()); LOG.info("[微信登录] 已恢复上次会话，无需重新扫码"); }
          catch (Exception error) { LOG.warn("[微信登录] 会话已失效，重新扫码，原因={}", rootMessage(error)); resumeStore.clear(); closeClient(current); loggedIn = false; }
        }
        if (!loggedIn) {
          LOG.info("[微信登录] 正在获取二维码");
          Path page = qrPage.render(current.executeLogin());
          LOG.info("[微信登录] 请打开二维码页面扫码，地址={}", page.toUri());
          qrPage.open(page);
          loggedIn = waitForLogin(current);
        }
        if (loggedIn) {
          onReady(current);
          pollMessagesUntilStopped(current);
        }
        closeClient(current);
      } catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
      catch (Exception error) { LOG.error("[微信Bot] 连接失败，稍后重试，原因={}", rootMessage(error), error); sleep(3000); }
    }
  }

  private ILinkClient createClient(ResumeContext resume) {
    ILinkConfig config = ILinkConfig.builder().connectTimeoutMs(60000).readTimeoutMs(60000).writeTimeoutMs(60000).httpMaxRetries(5).retryBaseDelayMs(1000).retryMaxDelayMs(10000).loginTimeoutMs(300000).autoReconnectEnabled(true).build();
    ILinkClientBuilder builder = ILinkClient.builder().config(config)
        .onLogin(new OnLoginListener() {
          @Override public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext context) {
            if (client != null) resumeStore.save(client.exportResumeContext());
          }
          @Override public void onLoginFailure(Throwable error) { LOG.error("[微信登录] 失败，原因={}", rootMessage(error), error); }
        })
        .onMessage((OnMessageListener) messages -> {
          if (client != null) resumeStore.save(client.exportResumeContext());
          for (WeixinMessage message : messages) handleMessage(client, message);
        });
    if (resume != null) builder.resumeContext(resume);
    return builder.build();
  }

  private boolean waitForLogin(ILinkClient current) throws InterruptedException {
    LoginStatus.Status last = null;
    CompletableFuture<?> future = current.getLoginFuture();
    while (running.get() && !current.isLoggedIn()) {
      LoginStatus status = current.getLoginStatus();
      if (status.getStatus() != last) { LOG.info("[微信登录] 状态={}", status.getStatus()); last = status.getStatus(); }
      if (status.getStatus() == LoginStatus.Status.ERROR || status.getStatus() == LoginStatus.Status.EXPIRED) return false;
      if (future != null && future.isDone()) { try { future.join(); } catch (CompletionException | CancellationException error) { return false; } return current.isLoggedIn(); }
      Thread.sleep(LOGIN_STATUS_INTERVAL_MS);
    }
    return current.isLoggedIn();
  }

  private void onReady(ILinkClient current) {
    String userId = current.getLoginContext() == null ? "" : current.getLoginContext().getUserId();
    if (userId.isBlank()) return;
    pendingGreetingUserId = userId;
    scheduleReminderDispatch(current, userId);
    if (hasSendContext(current, userId)) {
      schedulePendingGreeting(current, userId);
    } else {
      LOG.info("[微信Bot] 登录成功，等待用户第一条消息刷新会话令牌后发送简报，用户={}", userId);
    }
  }

  private void handleMessage(ILinkClient current, WeixinMessage message) {
    if (current == null || message == null) return;
    String userId = message.getFrom_user_id();
    String text = textOf(message);
    if (userId == null || userId.isBlank() || text.isBlank()) return;
    schedulePendingGreeting(current, userId);
    LOG.info("[工作流] 收到消息｜用户消息｜待判断｜{}", logText(text));
    try {
      String reply;
      String route;
      if (isNoteCapture(text)) { route = "笔记记录"; reply = planner.capture(userId, text); }
      else if (isReadOnlyCommand(text)) { route = "只读查询"; reply = planner.command(userId, text); }
      else if (text.contains("计划网页") || text.contains("工作台") || text.equals("打开计划")) { route = "网页链接"; reply = webLinkMessage(); }
      else { route = "AI对话"; reply = planner.aiChat(userId, text); }
      LOG.info("[工作流] 路由完成｜路由决策｜{}｜进入{}处理", route, route);
      current.sendText(userId, reply);
      LOG.info("[工作流] 返回消息｜Agent消息｜{}｜{}", route, logText(reply));
    } catch (Exception error) {
      String detail = rootMessage(error);
      LOG.error("[工作流] 执行失败｜错误消息｜AI对话｜{}", detail);
      try {
        String fallback = detail.toLowerCase().contains("timed out")
            ? "AI 响应超时了，请稍后再试一次。"
            : "这条消息暂时处理失败，请稍后再试。";
        current.sendText(userId, fallback);
        LOG.warn("[工作流] 返回消息｜Agent消息｜错误处理｜{}", fallback);
      } catch (Exception sendError) {
        LOG.error("[工作流] 错误反馈失败｜错误消息｜错误处理｜{}", rootMessage(sendError));
      }
    }
  }

  private void sendPendingGreeting(ILinkClient current, String userId) throws Exception {
    if (!userId.equals(pendingGreetingUserId)) return;
    String briefing = planner.briefing(userId);
    // 简报使用 Markdown 链接；普通命令仍保留完整 URL，便于不支持 Markdown 的客户端复制。
    String message = (briefing.isBlank() ? "今日简报暂时没有生成。" : briefing) + "\n\n" + markdownWebLink();
    current.sendText(userId, message);
    pendingGreetingUserId = "";
    LOG.info("[微信简报反馈] 用户={} 内容={}", userId, logText(message));
  }

  private void schedulePendingGreeting(ILinkClient current, String userId) {
    if (!userId.equals(pendingGreetingUserId) || !briefingInProgress.compareAndSet(false, true)) return;
    briefingExecutor.execute(() -> {
      try { sendPendingGreeting(current, userId); }
      catch (Exception error) { LOG.error("[微信简报发送失败] 用户={} 原因={}", userId, rootMessage(error), error); }
      finally { briefingInProgress.set(false); }
    });
  }

  private boolean hasSendContext(ILinkClient current, String userId) {
    try {
      ResumeContext resume = current.exportResumeContext();
      if (resume == null || resume.getConversationContextMap() == null) return false;
      var context = resume.getConversationContextMap().get(userId);
      return context != null && context.hasContextToken();
    } catch (Exception ignored) { return false; }
  }

  private String webLinkMessage() { return "点击此链接：\n" + webUrl; }

  private String markdownWebLink() { return "[点击此链接](" + webUrl + ")"; }

  private String textOf(WeixinMessage message) {
    if (message.getItem_list() == null) return "";
    return message.getItem_list().stream().filter(item -> item != null && item.getText_item() != null).map(item -> item.getText_item().getText()).filter(value -> value != null && !value.isBlank()).findFirst().orElse("").trim();
  }

  private boolean isNoteCapture(String text) { return text.startsWith("笔记:") || text.startsWith("笔记："); }
  private boolean isReadOnlyCommand(String text) { String value = text.replaceAll("[\\s，。！？、,.!?]", ""); return value.equals("今天还有什么") || value.equals("今天还有哪些") || value.equals("计划完成得怎么样"); }
  private String configuredWebUrl() {
    String configured = EnvironmentConfig.value("PLANNER_WEB_URL", "web.url", "");
    if (configured.isBlank()) return "http://" + lanAddress() + ":8081/";
    try {
      URI uri = URI.create(configured);
      if (uri.getPort() == 4173) {
        return new URI(uri.getScheme(), uri.getUserInfo(), uri.getHost(), 8081, uri.getPath(), uri.getQuery(), uri.getFragment()).toString();
      }
    } catch (Exception ignored) { }
    return configured;
  }
  private String lanAddress() {
    try {
      String fallback = "";
      String ethernet = "";
      for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
        if (!network.isUp() || network.isLoopback()) continue;
        String name = (network.getName() + " " + network.getDisplayName()).toLowerCase();
        if (name.contains("virtual") || name.contains("vethernet") || name.contains("hyper-v")
            || name.contains("vmware") || name.contains("virtualbox") || name.contains("docker") || name.contains("wsl")) continue;
        for (InetAddress address : Collections.list(network.getInetAddresses())) {
          if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
          if (fallback.isBlank()) fallback = address.getHostAddress();
          if (name.contains("wi-fi") || name.contains("wifi") || name.contains("wlan") || name.contains("wireless")) return address.getHostAddress();
          if (ethernet.isBlank() && name.contains("ethernet")) ethernet = address.getHostAddress();
        }
      }
      if (!ethernet.isBlank()) return ethernet;
      if (!fallback.isBlank()) return fallback;
    } catch (Exception ignored) { }
    return "127.0.0.1";
  }
  /**
   * SDK 不会自动拉取微信消息，必须持续调用 getUpdates()。
   * 收到消息后 SDK 会更新会话令牌，提醒调度器才能向该用户发送主动消息。
   */
  private void pollMessagesUntilStopped(ILinkClient current) throws InterruptedException {
    while (running.get() && client == current && current.isLoggedIn()) {
      try {
        current.getUpdates();
      } catch (Exception error) {
        if (!running.get()) return;
        LOG.warn("[微信消息轮询失败] 原因={}", rootMessage(error));
        Thread.sleep(3000L);
      }
    }
  }
  private void sleep(long millis) { try { Thread.sleep(millis); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } }
  private void closeClient(ILinkClient target) { try { target.cancelLogin(); target.close(); } catch (Exception ignored) { } }
  private String logText(String value) {
    String normalized = value == null ? "" : value.replace("\r", "\\r").replace("\n", "\\n");
    return normalized.length() <= 8000 ? normalized : normalized.substring(0, 8000) + "... [已截断，原长度=" + normalized.length() + "]";
  }

  private void scheduleReminderDispatch(ILinkClient current, String userId) {
    ScheduledFuture<?> previous = reminderTask;
    if (previous != null) previous.cancel(false);
    reminderTask = reminderExecutor.scheduleWithFixedDelay(() -> {
      // 微信 SDK 登录成功不等于已经拿到可发送消息的会话令牌；令牌未刷新时先等待用户消息。
      if (!running.get() || client != current || !current.isLoggedIn() || !hasSendContext(current, userId)) return;
      try {
        Database.Context context = database.contextForExternalUser(userId);
        reminders.retryMissingContext(context);
        reminders.dispatchWechat(context, message -> current.sendText(userId, message + "\n\n" + markdownWebLink()));
      } catch (Exception error) {
        LOG.warn("[微信提醒失败] 用户={} 原因={}", userId, rootMessage(error));
      }
    }, 0, 30, TimeUnit.SECONDS);
  }
  private String rootMessage(Throwable error) { Throwable current = error; while (current.getCause() != null && current.getCause() != current) current = current.getCause(); return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage(); }

  @Override public void close() { running.set(false); briefingExecutor.shutdownNow(); reminderExecutor.shutdownNow(); if (reminderTask != null) reminderTask.cancel(false); if (worker != null) worker.interrupt(); if (client != null) { try { resumeStore.save(client.exportResumeContext()); } catch (Exception ignored) { } closeClient(client); } }
}
