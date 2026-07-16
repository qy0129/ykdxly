package com.wechat.link.bot;

import com.openilink.ILinkClient;
import com.openilink.auth.LoginCallbacks;
import com.openilink.model.FileItem;
import com.openilink.model.ImageItem;
import com.openilink.model.MessageItem;
import com.openilink.model.VoiceItem;
import com.openilink.model.MessageItemType;
import com.openilink.model.WeixinMessage;
import com.openilink.model.response.LoginResult;
import com.openilink.monitor.MonitorOptions;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class WechatBotRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final Map<String, String> contextTokenCache = new ConcurrentHashMap<>();

    @Value("${ilink.token:}")
    private String token;

    @Override
    public void run(String... args) {
        log.info("wechat-link 启动中...");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("正在停止...");
            stopFlag.set(true);
        }));

        ILinkClient client = ILinkClient.builder()
                .token(token)
                .botType("3")
                .version("1.0.0")
                .build();

        LoginResult result = client.loginWithQR(new LoginCallbacks() {
            @Override
            public void onQRCode(String qrCodeUrl) {
                log.info("====================================");
                log.info("请用浏览器打开以下链接，然后用微信扫码登录：");
                log.info(qrCodeUrl);
                log.info("====================================");
            }

            @Override
            public void onScanned() {
                log.info("已扫码，请在微信上确认登录...");
            }

            @Override
            public void onExpired(int attempt, int maxAttempts) {
                log.warn("二维码已过期，正在刷新 ({}/{})", attempt, maxAttempts);
            }
        });

        if (!result.isConnected()) {
            log.error("登录失败: {}", result.getMessage());
            return;
        }

        log.info("登录成功! BotID={}", result.getBotId());

        MonitorOptions options = MonitorOptions.builder()
                .onBufUpdate(buf -> log.debug("sync buf: {}", buf))
                .onError(e -> log.warn("监听异常: {}", e.getMessage()))
                .onSessionExpired(() -> {
                    log.error("会话已过期，请重启应用重新扫码");
                    stopFlag.set(true);
                })
                .build();

        client.monitor(msg -> {
            contextTokenCache.put(msg.getFromUserId(), msg.getContextToken());
            handleMessage(client, msg);
        }, options, stopFlag);

        log.info("消息监听已停止");
    }

    private void handleMessage(ILinkClient client, WeixinMessage msg) {
        String userId = msg.getFromUserId();
        List<MessageItem> items = msg.getItemList();
        if (items == null || items.isEmpty()) return;

        for (MessageItem item : items) {
            switch (item.getType()) {
                case TEXT -> handleText(client, userId, item);
                case IMAGE -> handleImage(client, userId, item);
                case FILE -> handleFile(client, userId, item);
                case VOICE -> handleVoice(client, userId, item);
                default -> log.info("收到其他类型消息: {}", item.getType());
            }
        }
    }

    private void handleText(ILinkClient client, String userId, MessageItem item) {
        String text = item.getTextItem() != null ? item.getTextItem().getText() : "";
        log.info("收到文本消息 [{}]: {}", userId, text);
        String reply = getTextReply(text);
        client.push(userId, reply);
        log.info("回复 [{}]: {}", userId, reply);
    }

    private void handleImage(ILinkClient client, String userId, MessageItem item) {
        ImageItem image = item.getImageItem();
        log.info("收到图片消息 [{}]: URL={}, 大小={}", userId,
                image != null ? image.getUrl() : "N/A",
                image != null ? image.getHdSize() : "N/A");
        client.push(userId, "收到你发的图片了！");
    }

    private void handleFile(ILinkClient client, String userId, MessageItem item) {
        FileItem file = item.getFileItem();
        String fileName = file != null ? file.getFileName() : "未知文件";
        log.info("收到文件消息 [{}]: 文件名={}, 大小={}", userId, fileName, file != null ? file.getLen() : "N/A");
        client.push(userId, "收到你发的文件: " + fileName);
    }

    private void handleVoice(ILinkClient client, String userId, MessageItem item) {
        VoiceItem voice = item.getVoiceItem();
        int duration = voice != null && voice.getPlayTime() != null ? voice.getPlayTime() : 0;
        String transcript = voice != null ? voice.getText() : null;
        log.info("收到语音消息 [{}]: 时长={}s, 转文字={}", userId, duration, transcript != null ? transcript : "无");
        if (transcript != null && !transcript.isBlank()) {
            client.push(userId, "收到你的语音，你说的是: " + transcript);
        } else {
            client.push(userId, "收到你的语音了！");
        }
    }

    private String getTextReply(String text) {
        if (text.contains("你好") || text.contains("hi") || text.contains("Hi")) {
            return "你好呀！我是微信机器人~";
        }
        if (text.contains("时间")) {
            return java.time.LocalDateTime.now().toString();
        }
        if (text.contains("天气")) {
            return "今天天气不错，适合 coding！";
        }
        if (text.contains("谁") || text.contains("你叫什么")) {
            return "我是 wechat-link 机器人！";
        }
        if (text.contains("帮助") || text.contains("help")) {
            return "支持功能：\n1. 文本自动回复\n2. 图片识别\n3. 文件识别\n4. 语音识别\n发点消息试试吧！";
        }
        return "收到: " + text;
    }

    @PreDestroy
    public void onDestroy() {
        stopFlag.set(true);
    }
}
