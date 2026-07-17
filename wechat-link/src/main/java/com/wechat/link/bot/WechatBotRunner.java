package com.wechat.link.bot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.FileItem;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.facade.LLMMessageFacade;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 微信机器人 - 单文件入口
 * <p>
 * 基于 wechat-ilink-sdk 2.3.3，负责：
 * 1. 扫码登录
 * 2. 消息接收与分发（根据 item 内部字段判断类型，而非 type 数字）
 * 3. 调用 LLMMessageFacade 获取 AI 回复
 * 4. 回复渲染与发送（文本 / 图片）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatBotRunner implements CommandLineRunner {

    private static final Pattern IMAGE_URL_PATTERN =
            Pattern.compile("(?i)https?://.+\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$");

    /** LLM 消息调度门面（核心大脑，不做任何修改） */
    private final LLMMessageFacade llmMessageFacade;

    private volatile ILinkClient client;

    @Override
    public void run(String... args) {
        log.info("===== wechat-link 启动中（wechat-ilink-sdk 2.3.3）=====");

        // 构建客户端，注册登录 & 消息监听器
        client = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("登录成功! BotID={}, UserID={}", ctx.getBotId(), ctx.getUserId());
                    }

                    @Override
                    public void onLoginFailure(Throwable ex) {
                        log.error("登录失败", ex);
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override
                    public void onMessages(List<WeixinMessage> messages) {
                        if (messages == null) return;
                        for (WeixinMessage msg : messages) {
                            try {
                                handleMessage(msg);
                            } catch (SessionExpiredException e) {
                                log.error("会话已过期，请重启应用重新扫码", e);
                            } catch (Exception e) {
                                log.error("处理消息异常 user={}", msg.getFrom_user_id(), e);
                            }
                        }
                    }
                })
                .build();

        // 执行扫码登录
        String qrUrl = client.executeLogin();
        log.info("====================================");
        log.info("请用浏览器打开以下链接，然后用微信扫码登录：");
        log.info(qrUrl);
        log.info("====================================");
        log.info("等待扫码确认中...");
    }

    // ==================== 消息分发 ====================
    // 注意：不依赖 item.getType() 数字，改为根据 item 内部字段是否存在来判断真实类型
    // 这样避免不同 SDK 版本对 type 编号定义不一致导致的路由错误

    private void handleMessage(WeixinMessage msg) {
        String userId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                // 优先判断文本（文本消息 text_item 不为空）
                handleText(userId, item);
            } else if (item.getImage_item() != null) {
                // 图片消息（image_item 不为空）
                handleImage(userId, item);
            } else if (item.getVoice_item() != null) {
                // 语音消息（voice_item 不为空）
                handleVoice(userId, item);
            } else if (item.getFile_item() != null) {
                // 文件消息（file_item 不为空）
                handleFile(userId, item);
            } else if (item.getVideo_item() != null) {
                // 视频消息
                log.info("收到视频 [{}]，暂不支持", userId);
                sendTextSafe(userId, "暂不支持视频解析，请发送文字、图片或语音。");
            } else {
                log.debug("收到未识别的消息 user={}, type={}", userId, item.getType());
            }
        }
    }

    // ==================== 文本消息 ====================

    private void handleText(String userId, MessageItem item) {
        String text = item.getText_item().getText();
        log.info("收到文本 [{}]: {}", userId, text);

        // 文本直接走 LLM 对话
        LLMResponse response = callLLM(userId, text, "TEXT", null);
        sendReply(userId, response);
    }

    // ==================== 图片消息 ====================

    private void handleImage(String userId, MessageItem item) {
        log.info("收到图片 [{}]", userId);
        try {
            // 通过 SDK 下载并解密图片，得到原始 byte[]
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            // 编码为 base64 data URI，传给视觉模型
            String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);

            LLMResponse response = callLLM(userId, "请描述这张图片的内容", "IMAGE", dataUri);
            sendReply(userId, response);
        } catch (IOException e) {
            log.error("下载图片失败 user={}", userId, e);
            sendTextSafe(userId, "图片下载失败，请重新发送。");
        }
    }

    // ==================== 语音消息 ====================

    private void handleVoice(String userId, MessageItem item) {
        VoiceItem voice = item.getVoice_item();
        String transcript = voice.getText();  // 微信语音转文字结果（可能为 null）
        Integer playtime = voice.getPlaytime();
        log.info("收到语音 [{}]: 时长={}ms, 转文字={}", userId,
                playtime, transcript != null ? transcript : "无");

        if (transcript != null && !transcript.isBlank()) {
            // 有转文字结果 → 当作文本消息处理，走 LLM 对话
            LLMResponse response = callLLM(userId, transcript, "TEXT", null);
            sendReply(userId, response);
        } else {
            // 无转文字 → 提示用户
            sendTextSafe(userId, "收到语音消息，但未识别到文字内容。请尝试发送文字或开启微信语音转文字功能。");
        }
    }

    // ==================== 文件消息 ====================

    private void handleFile(String userId, MessageItem item) {
        FileItem file = item.getFile_item();
        String fileName = file.getFile_name() != null ? file.getFile_name() : "未知文件";
        String fileLen = file.getLen();  // 文件大小（字符串）
        log.info("收到文件 [{}]: 文件名={}, 大小={}", userId, fileName, fileLen);

        // 将文件名和信息传给 LLM，让 AI 给出合理回复
        String prompt = "用户发送了一个文件：" + fileName + "。请告诉用户你已收到，并询问他需要你如何处理这个文件。";
        LLMResponse response = callLLM(userId, prompt, "TEXT", null);
        sendReply(userId, response);
    }

    // ==================== LLM 调用 ====================

    private LLMResponse callLLM(String userId, String content, String messageType, String mediaUrl) {
        LLMRequest request = LLMRequest.builder()
                .userId(userId)
                .sessionId(userId)
                .content(content)
                .messageType(messageType)
                .mediaUrl(mediaUrl)
                .build();
        return llmMessageFacade.handleMessage(request);
    }

    // ==================== 回复发送 ====================

    private void sendReply(String userId, LLMResponse response) {
        if (response == null) {
            sendTextSafe(userId, "系统繁忙，请稍后再试。");
            return;
        }
        if (!"SUCCESS".equals(response.getStatus())) {
            String errMsg = response.getErrorMsg() != null ? response.getErrorMsg() : "未知错误";
            sendTextSafe(userId, "抱歉，处理出现问题：" + errMsg);
            return;
        }

        String content = response.getContent();
        if (content == null || content.isBlank()) {
            sendTextSafe(userId, "已收到你的消息。");
            return;
        }

        // 如果回复内容是图片 URL（如文生图返回），则下载后以图片形式发送
        if (IMAGE_URL_PATTERN.matcher(content).matches()) {
            sendImageFromUrl(userId, content);
        } else {
            sendTextSafe(userId, content);
        }
    }

    /** 发送文本，异常时仅记录日志 */
    private void sendTextSafe(String userId, String text) {
        try {
            client.sendText(userId, text);
            log.info("回复文本 [{}]: {}", userId, text.length() > 50 ? text.substring(0, 50) + "..." : text);
        } catch (IOException e) {
            log.error("发送文本失败 user={}", userId, e);
        }
    }

    /** 下载公网图片并通过微信发送 */
    private void sendImageFromUrl(String userId, String imageUrl) {
        try {
            byte[] imageBytes = downloadBytes(imageUrl);
            String fileName = "img_" + System.currentTimeMillis() + ".jpg";
            client.sendImage(userId, imageBytes, fileName, null);
            log.info("回复图片 [{}]: {}", userId, imageUrl);
        } catch (Exception e) {
            log.error("发送图片失败 user={}, url={}", userId, imageUrl, e);
            sendTextSafe(userId, "图片发送失败，为你提供链接：" + imageUrl);
        }
    }

    /** 轻量 HTTP 下载工具 */
    private byte[] downloadBytes(String url) throws IOException {
        try (InputStream is = URI.create(url).toURL().openStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    // ==================== 生命周期 ====================

    @PreDestroy
    public void onDestroy() {
        log.info("应用关闭，释放资源...");
        if (client != null) {
            client.close();
            client = null;
        }
    }
}
