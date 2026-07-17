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
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.facade.LLMMessageFacade;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信机器人消息监听器
 * <p>
 * 负责微信端消息的收发，将消息转发给 LLMMessageFacade 进行智能处理。
 * </p>
 *
 * @author wechat-link
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatBotRunner implements CommandLineRunner {

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);
    private final Map<String, String> contextTokenCache = new ConcurrentHashMap<>();

    /** LLM 消息调度门面 */
    private final LLMMessageFacade llmMessageFacade;

    @Value("${ilink.token:}")
    private String token;

    @Override
    public void run(String... args) {
        log.info("wechat-link 启动中..");

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

    /**
     * 消息分发处理
     */
    private void handleMessage(ILinkClient client, WeixinMessage msg) {
        String userId = msg.getFromUserId();
        List<MessageItem> items = msg.getItemList();
        if (items == null || items.isEmpty()) {
            return;
        }

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

    /**
     * 处理文本消息 - 转发给 LLM
     */
    private void handleText(ILinkClient client, String userId, MessageItem item) {
        String text = item.getTextItem() != null ? item.getTextItem().getText() : "";
        log.info("收到文本消息 [{}]: {}", userId, text);

        LLMRequest request = LLMRequest.builder()
                .userId(userId)
                .sessionId(userId)
                .content(text)
                .messageType("TEXT")
                .build();

        LLMResponse response = llmMessageFacade.handleMessage(request);
        String reply = extractReply(response);
        client.push(userId, reply);
        log.info("回复 [{}]: {}", userId, reply);
    }

    /**
     * 处理图片消息 - 转发给多模态解析
     */
    private void handleImage(ILinkClient client, String userId, MessageItem item) {
        ImageItem image = item.getImageItem();
        String imageUrl = image != null ? image.getUrl() : null;
        log.info("收到图片消息 [{}]: URL={}", userId, imageUrl);

        LLMRequest request = LLMRequest.builder()
                .userId(userId)
                .sessionId(userId)
                .messageType("IMAGE")
                .mediaUrl(imageUrl)
                .build();

        LLMResponse response = llmMessageFacade.handleMessage(request);
        client.push(userId, extractReply(response));
    }

    /**
     * 处理文件消息 - 转发给文档解析
     */
    private void handleFile(ILinkClient client, String userId, MessageItem item) {
        FileItem file = item.getFileItem();
        String fileName = file != null ? file.getFileName() : "未知文件";
        log.info("收到文件消息 [{}]: 文件名={}", userId, fileName);

        LLMRequest request = LLMRequest.builder()
                .userId(userId)
                .sessionId(userId)
                .messageType("FILE")
                .content(fileName)
                .build();

        LLMResponse response = llmMessageFacade.handleMessage(request);
        client.push(userId, extractReply(response));
    }

    /**
     * 处理语音消息 - 如有转文字则走 LLM 对话
     */
    private void handleVoice(ILinkClient client, String userId, MessageItem item) {
        VoiceItem voice = item.getVoiceItem();
        int duration = voice != null && voice.getPlayTime() != null ? voice.getPlayTime() : 0;
        String transcript = voice != null ? voice.getText() : null;
        log.info("收到语音消息 [{}]: 时长={}s, 转文字={}", userId, duration,
                transcript != null ? transcript : "无");

        LLMRequest request = LLMRequest.builder()
                .userId(userId)
                .sessionId(userId)
                .messageType("VOICE")
                .content(transcript)
                .build();

        LLMResponse response = llmMessageFacade.handleMessage(request);
        client.push(userId, extractReply(response));
    }

    /**
     * 从 LLMResponse 中提取回复文本
     */
    private String extractReply(LLMResponse response) {
        if (response == null) {
            return "系统繁忙，请稍后再试。";
        }
        if ("SUCCESS".equals(response.getStatus())) {
            return response.getContent();
        }
        return "抱歉，处理出现问题：" + response.getErrorMsg();
    }

    @PreDestroy
    public void onDestroy() {
        stopFlag.set(true);
    }
}
