package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ILinkBot {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final MessageDispatcher dispatcher = new MessageDispatcher();
    private ILinkClient client;

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
            if (qrcodeImg.startsWith("http")) {
                System.out.println("请在浏览器打开此链接扫码登录:");
                System.out.println(qrcodeImg + "\n");
            } else {
                String raw = qrcodeImg.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");
                Files.write(Path.of("qrcode.png"), Base64.getDecoder().decode(raw));
                System.out.println("二维码已保存到 qrcode.png，请打开并用微信扫码登录\n");
            }

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

    public static void main(String[] args) {
        ILinkBot bot = new ILinkBot();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在退出...");
            bot.stop();
        }));
        bot.start();
    }
}
