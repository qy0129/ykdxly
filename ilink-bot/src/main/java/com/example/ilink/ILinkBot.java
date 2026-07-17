package com.example.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public class ILinkBot {

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final AiService ai = new AiService(httpClient);
    private final CountDownLatch latch = new CountDownLatch(1);
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ILinkClient client;

    public void start() throws Exception {
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
                        for (WeixinMessage msg : messages) {
                            executor.submit(() -> handleMessage(ILinkBot.this.client, msg));
                        }
                    }
                })
                .build();
        this.client = client;

        System.out.println("正在获取登录二维码...");
        String qrcodeImg = client.executeLogin();

        if (qrcodeImg.startsWith("http")) {
            System.out.println("请浏览器打开此链接扫码登录:");
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
    }

    private void handleMessage(ILinkClient client, WeixinMessage msg) {
        try {
            String fromUser = msg.getFrom_user_id();
            List<com.github.wechat.ilink.sdk.core.model.MessageItem> items = msg.getItem_list();

            if (items == null || items.isEmpty()) return;

            com.github.wechat.ilink.sdk.core.model.MessageItem first = items.get(0);

            if (first.getText_item() != null) {
                String text = first.getText_item().getText();
                System.out.println("[" + fromUser + "] " + text);

                if (AiService.isDrawRequest(text)) {
                    System.out.println("[Bot] 正在准备绘图...");
                    String[] drawInfo = ai.callQwenDraw(text);
                    if (drawInfo != null) {
                        String enPrompt = drawInfo[0];
                        String cnDesc = drawInfo[1];
                        System.out.println("[Bot] 绘图prompt: " + enPrompt);
                        System.out.println("[Bot] 图片描述: " + cnDesc);
                        byte[] imgBytes = ai.callImageGeneration(enPrompt);
                        if (imgBytes != null) {
                            client.sendImage(fromUser, imgBytes, "draw.png", cnDesc);
                            System.out.println("[Bot] 图片已发送");
                        } else {
                            client.sendText(fromUser, "绘图失败，请重试");
                        }
                    } else {
                        client.sendText(fromUser, "绘图失败，无法理解你的描述");
                    }
                } else {
                    System.out.println("[Bot] 正在思考...");
                    String reply = ai.callQwen(text);
                    System.out.println("[Bot] → " + reply);
                    client.sendText(fromUser, reply);
                }
                return;
            }

            if (first.getImage_item() != null) {
                System.out.println("[" + fromUser + "] [图片] 正在分析...");
                byte[] imgBytes = client.downloadImageFromMessageItem(first);
                if (imgBytes != null && imgBytes.length > 0) {
                    String base64Image = Base64.getEncoder().encodeToString(imgBytes);
                    String reply = ai.callQwenWithImage("请描述这张图片的内容", base64Image);
                    System.out.println("[" + fromUser + "] [图片分析] " + reply);
                    client.sendText(fromUser, reply);
                } else {
                    client.sendText(fromUser, "图片下载失败");
                }
                return;
            }

            if (first.getVoice_item() != null) {
                String asrText = first.getVoice_item().getText();
                if (asrText != null && !asrText.isBlank()) {
                    System.out.println("[" + fromUser + "] [语音] " + asrText);
                    if (AiService.isDrawRequest(asrText)) {
                        System.out.println("[Bot] 正在准备绘图...");
                        String[] drawInfo = ai.callQwenDraw(asrText);
                        if (drawInfo != null) {
                            String enPrompt = drawInfo[0];
                            String cnDesc = drawInfo[1];
                            System.out.println("[Bot] 绘图prompt: " + enPrompt);
                            System.out.println("[Bot] 图片描述: " + cnDesc);
                            byte[] imgBytes = ai.callImageGeneration(enPrompt);
                            if (imgBytes != null) {
                                client.sendImage(fromUser, imgBytes, "draw.png", cnDesc);
                                System.out.println("[Bot] 图片已发送");
                            } else {
                                client.sendText(fromUser, "绘图失败，请重试");
                            }
                        } else {
                            client.sendText(fromUser, "绘图失败，无法理解你的描述");
                        }
                    } else {
                        System.out.println("[Bot] 正在思考...");
                        String reply = ai.callQwen(asrText);
                        System.out.println("[Bot] → " + reply);
                        client.sendText(fromUser, reply);
                    }
                } else {
                    System.out.println("[" + fromUser + "] [语音]（无ASR文本）");
                }
                return;
            }

            if (first.getFile_item() != null) {
                String fileName = first.getFile_item().getFile_name();
                System.out.println("[" + fromUser + "] [文件] " + fileName);
                return;
            }

            System.out.println("[" + fromUser + "] [未知消息类型]");
        } catch (Exception e) {
            System.err.println("处理消息异常: " + e.getMessage());
        }
    }

    public void stop() {
        executor.shutdownNow();
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
        }
        latch.countDown();
    }

    public static void main(String[] args) throws Exception {
        ILinkBot bot = new ILinkBot();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在退出...");
            bot.stop();
        }));

        bot.start();
    }
}
