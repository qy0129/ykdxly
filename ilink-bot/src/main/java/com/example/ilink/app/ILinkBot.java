package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * iLink 机器人应用入口。
 *
 * <p>负责创建微信 SDK 客户端、执行扫码登录、启动消息分发器，
 * 并在程序退出时释放线程池和 SDK 连接。具体业务处理由
 * {@link MessageDispatcher} 完成。</p>
 */
public class ILinkBot {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private ILinkClient client;

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

            ILinkClient client = ILinkClient.builder()
                    .onMessage(new OnMessageListener() {
                        /** 将 SDK 收到的消息提交到后台线程逐条处理。 */
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            for (WeixinMessage message : messages) {
                                executor.submit(() -> dispatcher.handleMessage(ILinkBot.this.client, message));
                            }
                        }
                    })
                    .build();
            this.client = client;

            String qrcodeImg = client.executeLogin();
            showLoginQrCode(qrcodeImg);

            System.out.println("等待扫码中...");
            while (!client.isLoggedIn()) {
                Thread.sleep(4000);
                System.out.println("  状态: 等待扫码...");
            }

            System.out.println("登录成功！监听器已就绪，等待消息... (Ctrl+C 退出)\n");
            latch.await();
            executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            System.err.println("启动失败: " + e.getMessage());
        }
    }

    /** 根据 SDK 返回的二维码链接或 Base64 数据展示登录二维码。 */
    private void showLoginQrCode(String qrcode) throws Exception {
        if (qrcode.startsWith("http")) {
            System.out.println("正在打开微信登录二维码页面:");
            System.out.println(qrcode + "\n");
            openInBrowser(qrcode);
            return;
        }

        String raw = qrcode.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");
        Path path = Path.of("qrcode.png").toAbsolutePath();
        Files.write(path, Base64.getDecoder().decode(raw));
        System.out.println("二维码已保存到 " + path + "，请用微信扫码登录\n");
        openFile(path);
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

    /** 尝试使用系统默认图片查看器打开二维码文件。 */
    private void openFile(Path path) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(path.toFile());
            }
        } catch (Exception e) {
            System.err.println("无法自动打开二维码图片，请手动打开: " + path);
        }
    }

    /** 停止线程池、消息分发器和微信客户端。 */
    public void stop() {
        executor.shutdownNow();
        dispatcher.close();
        if (client != null) {
            try {
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
