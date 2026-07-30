package com.example.ilink.bootstrap;

import com.example.ilink.adapter.inbound.wechat.LoginQrPage;
import com.example.ilink.adapter.inbound.wechat.MessageDispatcher;
import com.example.ilink.adapter.inbound.wechat.WechatMessageAdapter;
import com.example.ilink.application.messaging.IncomingMessage;
import com.example.ilink.application.messaging.MessageSerialExecutor;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.platform.sdk.SdkResumeContextStore;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.ILinkClientBuilder;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginStatus;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.awt.Desktop;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * iLink 机器人应用入口。
 *
 * <p>负责创建微信 SDK 客户端、执行扫码登录、启动消息分发器，
 * 并在程序退出时释放线程池和 SDK 连接。具体业务处理由
 * {@link MessageDispatcher} 完成。</p>
 */
public class ILinkBot {

    /** 登录状态轮询间隔，避免频繁请求登录接口。 */
    private static final long LOGIN_STATUS_INTERVAL_MS = 2000L;
    /** 登录失败后重新获取二维码前的等待时间。 */
    private static final long LOGIN_RETRY_DELAY_MS = 3000L;

    private final CountDownLatch latch = new CountDownLatch(1);
    private final ApplicationBootstrap bootstrap;
    private final MessageDispatcher dispatcher;
    private final WechatMessageAdapter messageAdapter;
    private final MessageSerialExecutor messageExecutor;
    private final LoginQrPage loginQrPage;
    private final SdkResumeContextStore resumeContextStore;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private ILinkClient client;

    public ILinkBot() {
        this(ApplicationBootstrap.create());
    }

    ILinkBot(ApplicationBootstrap bootstrap) {
        this.bootstrap = bootstrap;
        this.dispatcher = bootstrap.messageDispatcher();
        this.messageAdapter = bootstrap.messageAdapter();
        this.messageExecutor = bootstrap.messageExecutor();
        this.loginQrPage = bootstrap.loginQrPage();
        this.resumeContextStore = bootstrap.resumeContextStore();
    }

    /** 启动机器人，完成登录并进入消息监听循环。 */
    public void start() {
        try {
            System.out.println("========================================");
            System.out.println("  iLink 微信机器人 - 千问智能版");
            System.out.println("  SDK: wechat-ilink-sdk v2.3.3");
            System.out.println("  AI: Qwen (" + Config.MODEL + ")");
            System.out.println("========================================\n");

            if (Config.API_KEY == null || Config.API_KEY.isBlank()) {
                System.err.println("错误: API Key 未正确配置");
                System.exit(1);
            }

            while (running.get() && !isLoggedIn()) {
                ILinkClient loginClient = null;
                boolean loginSucceeded = false;
                try {
                    ResumeContext savedContext = resumeContextStore.load();
                    loginClient = createClient(savedContext);
                    this.client = loginClient;

                    if (savedContext != null && loginClient.isLoggedIn()) {
                        try {
                            loginClient.getUpdates();
                            loginSucceeded = true;
                            resumeContextStore.save(loginClient.exportResumeContext());
                            dispatcher.onClientReady(loginClient);
                            System.out.println("[登录] 已恢复上次会话，无需重新扫码");
                        } catch (Exception resumeError) {
                            System.err.println("[登录] 上次会话已失效，改用扫码登录: " + rootMessage(resumeError));
                            resumeContextStore.clear();
                            closeClient(loginClient);
                            loginClient = createClient(null);
                            this.client = loginClient;
                        }
                    }

                    if (!loginSucceeded) {
                        System.out.println("[登录] 正在获取二维码...");
                        String qrcodeImg = loginClient.executeLogin();
                        showLoginQrCode(qrcodeImg);
                        loginSucceeded = waitForLogin(loginClient);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    System.err.println("[登录] 网络异常，准备重试: " + rootMessage(e));
                } finally {
                    if (!loginSucceeded && loginClient != null) {
                        closeClient(loginClient);
                    }
                }

                if (!loginSucceeded && running.get()) {
                    System.out.println("[登录] 本次登录未完成，" + (LOGIN_RETRY_DELAY_MS / 1000)
                            + " 秒后刷新二维码...\n");
                    Thread.sleep(LOGIN_RETRY_DELAY_MS);
                }
            }

            if (!running.get() || !isLoggedIn()) {
                return;
            }

            System.out.println("登录成功！监听器已就绪，等待消息... (Ctrl+C 退出)\n");
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
        }
    }

    /** 创建带网络重试、超时和登录回调的 SDK 客户端。 */
    private ILinkClient createClient(ResumeContext resumeContext) {
        ILinkConfig config = ILinkConfig.builder()
                .connectTimeoutMs(60000)
                .readTimeoutMs(60000)
                .writeTimeoutMs(60000)
                .httpMaxRetries(5)
                .retryBaseDelayMs(1000)
                .retryMaxDelayMs(10000)
                .loginTimeoutMs(300000)
                .autoReconnectEnabled(true)
                .build();

        ILinkClientBuilder builder = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    /** 登录成功时输出中文状态，便于在控制台确认登录结果。 */
                    @Override
                    public void onLoginSuccess(com.github.wechat.ilink.sdk.core.login.LoginContext context) {
                        System.out.println("[登录] 登录成功");
                        resumeContextStore.save(ILinkBot.this.client.exportResumeContext());
                        // 初次扫码和自动重连都会进入这里，每次登录都触发离线补发和温柔简报。
                        dispatcher.onClientReady(ILinkBot.this.client, context.getUserId());
                    }

                    /** 登录失败时输出 SDK 返回的具体原因。 */
                    @Override
                    public void onLoginFailure(Throwable error) {
                        System.err.println("[登录] 登录失败: " + rootMessage(error));
                    }
                })
                .onMessage(new OnMessageListener() {
                    /** 将 SDK 收到的消息提交到后台线程逐条处理。 */
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        resumeContextStore.save(ILinkBot.this.client.exportResumeContext());
                        for (WeixinMessage message : messages) {
                            try {
                                IncomingMessage incoming = messageAdapter.adapt(ILinkBot.this.client, message);
                                messageExecutor.execute(incoming.principalId(), () -> {
                                    try {
                                    dispatcher.handleMessage(ILinkBot.this.client, incoming);
                                    } catch (Exception error) {
                                        System.err.println("[Message dispatch] Failed to handle message: "
                                            + rootMessage(error));
                                    }
                                });
                            } catch (Exception error) {
                                System.err.println("[Message adapter] Failed to read WeChat message: "
                                        + rootMessage(error));
                            }
                        }
                    }
                });
        if (resumeContext != null) builder.resumeContext(resumeContext);
        return builder.build();
    }

    /** 轮询当前登录状态，遇到过期、错误或 Future 异常时结束本次登录。 */
    private boolean waitForLogin(ILinkClient loginClient) throws InterruptedException {
        LoginStatus.Status lastStatus = null;
        CompletableFuture<?> loginFuture = loginClient.getLoginFuture();

        while (running.get() && !loginClient.isLoggedIn()) {
            LoginStatus loginStatus = loginClient.getLoginStatus();
            LoginStatus.Status status = loginStatus.getStatus();
            if (status != lastStatus) {
                String detail = status == LoginStatus.Status.ERROR && loginStatus.getErrorMessage() != null
                        ? "，原因=" + loginStatus.getErrorMessage() : "";
                System.out.println("[登录] 状态=" + status + detail);
                lastStatus = status;
            }

            if (status == LoginStatus.Status.EXPIRED || status == LoginStatus.Status.ERROR) {
                return false;
            }

            if (loginFuture != null && loginFuture.isDone()) {
                try {
                    loginFuture.join();
                } catch (CompletionException | CancellationException e) {
                    System.err.println("[登录] 登录轮询结束: " + rootMessage(e));
                    return false;
                }
                return loginClient.isLoggedIn();
            }

            Thread.sleep(LOGIN_STATUS_INTERVAL_MS);
        }
        return loginClient.isLoggedIn();
    }

    /** 判断当前客户端是否已完成登录。 */
    private boolean isLoggedIn() {
        return client != null && client.isLoggedIn();
    }

    /** 关闭一次登录尝试使用的临时客户端。 */
    private void closeClient(ILinkClient target) {
        try {
            target.cancelLogin();
            target.close();
        } catch (Exception ignored) {
            // 关闭失败不影响下一次二维码登录。
        }
    }

    /** 提取异常根因，避免控制台只显示多层包装异常。 */
    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /** 根据 SDK 返回的二维码链接或 Base64 数据生成并打开扫码前端页面。 */
    private void showLoginQrCode(String qrcode) throws Exception {
        URI page = loginQrPage.render(qrcode);
        System.out.println("[登录] 已生成扫码页面，请使用微信扫描页面中的二维码：");
        System.out.println(page + "\n");
        openInBrowser(page.toString());
    }

    /** 尝试使用系统默认浏览器打开登录页面。 */
    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
            }
        } catch (Exception e) {
            System.err.println("无法自动打开浏览器，请手动打开上面的链接: " + e.getMessage());
        }
    }

    /** 停止线程池、消息分发器和微信客户端。 */
    public void stop() {
        running.set(false);
        bootstrap.close();
        if (client != null) {
            try {
                resumeContextStore.save(client.exportResumeContext());
                client.cancelLogin();
                client.close();
            } catch (Exception ignored) {}
        }
        latch.countDown();
    }

    /** Java 程序入口，注册退出钩子后启动机器人。 */
    public static void main(String[] args) {
        ILinkBot bot = new ILinkBot();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在退出...");
            bot.stop();
        }));
        bot.start();
    }
}
