package com.example.demo.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    private static final int MAX_HISTORY = 10;

    @Autowired
    private DashScopeService dashScopeService;

    private final Map<String, List<String[]>> conversationHistory = new ConcurrentHashMap<>();

    private ILinkClient client;
    private volatile boolean running = false;
    private volatile boolean loggedIn = false;

    public void start() {
        if (running) {
            log.warn("Bot 已在运行中");
            return;
        }
        running = true;
        new Thread(this::runBot, "wechat-bot-thread").start();
    }

    private void runBot() {
        try {
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .writeTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .retryBaseDelayMs(1000)
                    .retryMaxDelayMs(10000)
                    .heartbeatEnabled(true)
                    .heartbeatIntervalMs(30000)
                    .channelVersion("1.0.0")
                    .build();

            client = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            loggedIn = true;
                            log.info("登录成功！botId = {}", context.getBotId());
                            System.out.println("\n========== 登录成功！==========");
                            System.out.println("Bot ID: " + context.getBotId());
                            System.out.println("现在可以给 Bot 发送消息了！");
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("登录失败: {}", throwable.getMessage());
                            System.err.println("\n[ERROR] 登录失败: " + throwable.getMessage());
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            handleMessages(messages);
                        }
                    })
                    .build();

            String qrContent = client.executeLogin();
            System.out.println("\n========== 请扫描二维码登录微信 Bot ==========");
            System.out.println("二维码内容:");
            System.out.println(qrContent);
            System.out.println("==============================================");

            log.info("等待扫码登录...");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成，开始监听消息...");

            while (running && loggedIn) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null && !messages.isEmpty()) {
                        log.info("收到 {} 条新消息", messages.size());
                    }
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("获取消息异常: {}", e.getMessage());
                    Thread.sleep(5000);
                }
            }

        } catch (Exception e) {
            log.error("Bot 运行异常: {}", e.getMessage());
            System.err.println("[ERROR] Bot 运行异常: " + e.getMessage());
        }
    }

    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage msg : messages) {
            String userId = msg.getFrom_user_id();
            if (msg.getItem_list() == null) continue;

            for (MessageItem item : msg.getItem_list()) {
                if (item.getText_item() != null) {
                    String text = item.getText_item().getText();
                    log.info("收到来自 {} 的消息: {}", userId, text);
                    System.out.println("\n[消息] 来自 " + userId + ": " + text);
                    String reply = getReply(userId, text);
                    sendReply(userId, reply);
                } else if (item.getImage_item() != null) {
                    log.info("收到来自 {} 的图片消息", userId);
                    System.out.println("\n[消息] 来自 " + userId + ": [图片]");
                    try {
                        byte[] imageBytes = client.downloadImageFromMessageItem(item);
                        log.info("图片下载完成，大小: {} bytes", imageBytes.length);
                        String reply = dashScopeService.chatWithImage("请详细描述这张图片的内容", imageBytes, "image.png");
                        if (reply != null) {
                            log.info("AI 识别图片完成");
                            sendReply(userId, reply);
                        } else {
                            log.warn("视觉模型不可用，降级为文字提示");
                            sendReply(userId, "已收到您的图片。当前视觉模型不可用，请用文字描述你想问的问题。");
                        }
                    } catch (Exception e) {
                        log.error("图片处理异常: {}", e.getMessage());
                        sendReply(userId, "抱歉，图片处理失败，请用文字描述你的问题。");
                    }
                } else if (item.getVoice_item() != null) {
                    log.info("收到来自 {} 的语音消息", userId);
                    System.out.println("\n[消息] 来自 " + userId + ": [语音]");
                    try {
                        byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
                        log.info("语音下载完成，大小: {} bytes", voiceBytes.length);
                        String recognized = dashScopeService.transcribeAudio(voiceBytes);
                        if (recognized != null && !recognized.isBlank()) {
                            log.info("语音转写结果: {}", recognized);
                            System.out.println("[语音识别] " + recognized);
                            String reply = getReply(userId, recognized);
                            sendReply(userId, reply);
                        } else {
                            sendReply(userId, "语音识别暂不可用，请用文字描述你想问的问题。");
                        }
                    } catch (Exception e) {
                        log.error("语音处理异常: {}", e.getMessage());
                        sendReply(userId, "语音处理失败，请用文字描述你想问的问题。");
                    }
                } else if (item.getFile_item() != null) {
                    log.info("收到来自 {} 的文件消息", userId);
                    System.out.println("\n[消息] 来自 " + userId + ": [文件]");
                    try {
                        byte[] fileBytes = client.downloadFileFromMessageItem(item);
                        String fileName = item.getFile_item().getFile_name();
                        if (fileName == null || fileName.isBlank()) fileName = "file.bin";
                        log.info("文件下载完成，大小: {} bytes, 文件名: {}", fileBytes.length, fileName);
                        System.out.println("[文件] " + fileName + " (" + fileBytes.length + " bytes)");
                        String reply = dashScopeService.analyzeFile(fileBytes, fileName, "请总结这份文件的主要内容");
                        if (reply != null && !reply.isBlank()) {
                            log.info("AI 文件分析完成");
                            sendReply(userId, reply);
                        } else {
                            sendReply(userId, "文件识别暂不可用，请用文字描述你想问的问题。");
                        }
                    } catch (Exception e) {
                        log.error("文件处理异常: {}", e.getMessage());
                        sendReply(userId, "文件处理失败，请用文字描述你的问题。");
                    }
                } else if (item.getVideo_item() != null) {
                    log.info("收到来自 {} 的视频消息", userId);
                    System.out.println("\n[消息] 来自 " + userId + ": [视频]");
                    try {
                        byte[] videoBytes = client.downloadVideoFromMessageItem(item);
                        log.info("视频下载完成，大小: {} bytes", videoBytes.length);
                        String reply = dashScopeService.chatWithVideo(videoBytes, "video.mp4", "请详细描述这段视频的内容");
                        if (reply != null && !reply.isBlank()) {
                            log.info("AI 视频识别完成");
                            sendReply(userId, reply);
                        } else {
                            sendReply(userId, "视频识别暂不可用，请用文字描述你想问的问题。");
                        }
                    } catch (Exception e) {
                        log.error("视频处理异常: {}", e.getMessage());
                        sendReply(userId, "视频处理失败，请用文字描述你的问题。");
                    }
                }
            }
        }
    }

    private String getReply(String userId, String text) {
        if (text == null || text.isBlank()) return "请输入消息内容。";

        if (text.trim().equals("/clear")) {
            conversationHistory.remove(userId);
            return "会话历史已清除，我们可以重新开始对话了。";
        }

        List<String[]> history = conversationHistory.get(userId);
        String reply = dashScopeService.chat(text, history);

        if (history == null) {
            history = new ArrayList<>();
            conversationHistory.put(userId, history);
        }
        history.add(new String[]{text, reply});
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }

        return reply;
    }

    private void sendReply(String userId, String reply) {
        try {
            client.sendText(userId, reply);
            log.info("回复 {}: {}", userId, reply);
            System.out.println("[回复] " + userId + ": " + reply);
        } catch (Exception e) {
            log.error("发送消息失败: {}", e.getMessage());
        }
    }

    public void stop() {
        running = false;
        loggedIn = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭 client 异常: {}", e.getMessage());
            }
        }
        log.info("Bot 已停止");
    }

    @PreDestroy
    public void onDestroy() {
        stop();
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public boolean isRunning() {
        return running;
    }
}
