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
import com.wechat.link.llm.config.LLMProperties;
import com.wechat.link.llm.dto.LLMRequest;
import com.wechat.link.llm.dto.LLMResponse;
import com.wechat.link.llm.facade.LLMMessageFacade;
import com.wechat.link.llm.service.AudioProcessingService;
import org.apache.commons.io.FileUtils;
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
 * 4. 回复渲染与发送（文本 / 图片 / 语音）
 * 5. 语音 ASR 兜底（微信原生 ASR 失败时下载 SILK → 转码 → 远端 ASR）
 * 6. TTS 语音回复（文字 → 语音合成 → sendVoice）
 * 7. 文档处理（Tika 内容提取 → 文本对话 / Markdown → Python 转 docx/pdf → 文件发送）
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WechatBotRunner implements CommandLineRunner {

    private static final Pattern IMAGE_URL_PATTERN =
            Pattern.compile("(?i)https?://.+\\.(png|jpe?g|gif|webp|bmp)(\\?.*)?$");

    private final LLMMessageFacade llmMessageFacade;
    private final AudioProcessingService audioProcessingService;
    private final LLMProperties llmProperties;

    private volatile ILinkClient client;

    @Override
    public void run(String... args) {
        log.info("===== wechat-link 启动中（wechat-ilink-sdk 2.3.3）=====");

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

        String qrUrl = client.executeLogin();
        log.info("====================================");
        log.info("请用浏览器打开以下链接，然后用微信扫码登录：");
        log.info(qrUrl);
        log.info("====================================");
        log.info("等待扫码确认中...");
    }

    // ==================== 消息分发 ====================

    private void handleMessage(WeixinMessage msg) {
        String userId = msg.getFrom_user_id();
        List<MessageItem> items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        for (MessageItem item : items) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                handleText(userId, item);
            } else if (item.getImage_item() != null) {
                handleImage(userId, item);
            } else if (item.getVoice_item() != null) {
                handleVoice(userId, item);
            } else if (item.getFile_item() != null) {
                handleFile(userId, item);
            } else if (item.getVideo_item() != null) {
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

        LLMResponse response = callLLM(userId, text, "TEXT", null);
        sendReply(userId, response);
    }

    // ==================== 图片消息 ====================

    private void handleImage(String userId, MessageItem item) {
        log.info("收到图片 [{}]", userId);
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            String dataUri = "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);

            LLMResponse response = callLLM(userId, "请描述这张图片的内容", "IMAGE", dataUri);
            sendReply(userId, response);
        } catch (IOException e) {
            log.error("下载图片失败 user={}", userId, e);
            sendTextSafe(userId, "图片下载失败，请重新发送。");
        }
    }

    // ==================== 语音消息（完整链路） ====================

    private void handleVoice(String userId, MessageItem item) {
        VoiceItem voice = item.getVoice_item();
        String transcript = voice.getText();
        Integer playtime = voice.getPlaytime();
        Integer sampleRate = voice.getSample_rate();
        log.info("收到语音 [{}]: 时长={}ms, 转文字={}, 采样率={}",
                userId, playtime, transcript != null ? transcript : "无", sampleRate);

        if (transcript != null && !transcript.isBlank()) {
            // ===== 微信 ASR 成功：正常语音对话流程 =====
            log.info("[Voice Router] 微信原生 ASR 识别成功，直接走对话");
            LLMResponse response = callLLM(userId, transcript, "VOICE", null);
            sendReply(userId, response);

        } else {
            // ===== 微信 ASR 失败 → 直接提示用户发送文字 =====
            log.warn("[Voice Router] 微信原生 ASR 返回为空或识别失败");
            sendTextSafe(userId, "语音内容为空，请发送文字消息。");
        }

        /* ===== 已注释：自定义 ASR 兜底逻辑 =====
        else if (llmProperties.getVoice().getAsr().getEnabled()) {
            // 微信 ASR 失败 + 远端 ASR 已启用 → ASR 兜底
            log.warn("[Voice Router] 检测到微信原生 ASR 挂断/为空，正在启动远端高级 ASR 重新唤醒链路...");
            try {
                // 1. 下载原始 SILK 音频
                byte[] silkData = client.downloadVoiceFromMessageItem(item);
                log.info("[Voice Router] SILK 音频下载成功，大小: {}KB", silkData.length / 1024);

                // 2. SILK → WAV 转换
                int sr = (sampleRate != null && sampleRate > 0) ? sampleRate : 16000;
                byte[] wavData = audioProcessingService.silkToWav(silkData, sr);

                // 3. 调用远端 ASR 服务
                String asrText = audioProcessingService.transcribeAudio(wavData);

                if (asrText != null && !asrText.isBlank()) {
                    log.info("[Voice Router] 远端 ASR 识别成功，文本: {}",
                            asrText.length() > 50 ? asrText.substring(0, 50) + "..." : asrText);

                    // 4. ASR 结果走正常对话流程（标记为 VOICE 来源触发 NEED_VOICE_REPLY）
                    LLMResponse response = callLLM(userId, asrText, "VOICE", null);
                    sendReply(userId, response);
                } else {
                    log.warn("[Voice Router] 远端 ASR 也识别失败");
                    sendTextSafe(userId, "语音识别失败，请发送文字消息。");
                }

            } catch (Exception e) {
                log.error("[Voice Router] ASR 兜底流程异常", e);
                sendTextSafe(userId, "语音处理失败，请发送文字消息。");
            }

        } else {
            // 微信 ASR 失败 + 未启用 ASR 兜底
            log.warn("[Voice Router] 微信 ASR 失败，未启用远端 ASR 兜底");
            sendTextSafe(userId, "收到语音消息，但未识别到文字内容。请尝试发送文字或开启语音转文字功能。");
        }
        */
    }

    // ==================== 文件消息 ====================

    private void handleFile(String userId, MessageItem item) {
        FileItem file = item.getFile_item();
        String fileName = file.getFile_name() != null ? file.getFile_name() : "未知文件";
        log.info("收到文件 [{}]: 文件名={}, 大小={}", userId, fileName, file.getLen());

        try {
            byte[] fileBytes = client.downloadFileFromMessageItem(item);
            log.info("文件下载成功 [{}]: {} bytes", userId, fileBytes.length);

            LLMResponse response = llmMessageFacade.handleDocumentRead(userId, fileName, fileBytes);
            sendReply(userId, response);

        } catch (Exception e) {
            log.error("文件处理失败 [{}]", fileName, e);
            LLMResponse response = callLLM(userId, "用户发送了一个文件：" + fileName
                    + "，但你暂时无法解析。请回复用户说明已收到文件，并请用户尝试用文字描述需求。", "FILE", null);
            sendReply(userId, response);
        }
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

    // ==================== 回复发送（含 TTS） ====================

    private void sendReply(String userId, LLMResponse response) {
        if (response == null) {
            sendTextSafe(userId, "系统繁忙，请稍后再试。");
            return;
        }

        if (!"SUCCESS".equals(response.getStatus())) {
            String errMsg = response.getErrorMsg() != null ? response.getErrorMsg() : "未知错误";
            sendTextSafe(userId, errMsg);
            return;
        }

        // 优先处理文件发送（文档生成场景）
        if (response.getFileBytes() != null && response.getFileName() != null) {
            String fileName = response.getFileName();
            log.info("[Bot] 发送文件 [{}]: {}", userId, fileName);
            try {
                client.sendFile(userId, response.getFileBytes(), fileName,
                        String.valueOf(response.getFileBytes().length));
                if (response.getContent() != null && !response.getContent().isBlank()) {
                    sendTextSafe(userId, response.getContent());
                }
            } catch (IOException e) {
                log.error("[Bot] 文件发送失败 [{}]", fileName, e);
                if (response.getContent() != null && !response.getContent().isBlank()) {
                    sendTextSafe(userId, response.getContent());
                } else {
                    sendTextSafe(userId, "文件生成成功，但发送失败。");
                }
            }
            return;
        }

        // 优先处理图片列表（文生图场景）
        if (response.hasImages()) {
            if (response.getContent() != null && !response.getContent().isBlank()) {
                sendTextSafe(userId, response.getContent());
            }
            for (String imageUrl : response.getImageUrls()) {
                sendImageFromUrl(userId, imageUrl);
            }
            return;
        }

        String content = response.getContent();
        if (content == null || content.isBlank()) {
            sendTextSafe(userId, "已收到你的消息。");
            return;
        }

        // 检查是否需要 TTS 语音回复
        if (response.isNeedVoiceReply() && llmProperties.getVoice().getTts().getEnabled()) {
            sendTtsVoiceReply(userId, content);
            return;
        }

        // 兜底：如果文本本身是图片 URL，也以图片形式发送
        if (IMAGE_URL_PATTERN.matcher(content).matches()) {
            sendImageFromUrl(userId, content);
        } else {
            sendTextSafe(userId, content);
        }
    }

    // ==================== TTS 语音回复（MP3 优先，WAV 兜底） ====================

    /** {instruction:...} 前缀正则 */
    private static final Pattern INSTRUCTION_PREFIX =
            Pattern.compile("^\\s*\\{instruction:([^}]*)\\}\\s*", Pattern.DOTALL);

    /**
     * 将文本合成为语音并发送 MP3 文件，若失败则降级为 WAV 文件
     * <p>
     * 解析 LLM 回复开头的 {@code {instruction:语气描述}} 前缀传给 TTS 指令控制。
     * 前缀不会显示给用户。
     * </p>
     */
    private void sendTtsVoiceReply(String userId, String text) {
        // 提取 TTS 指令前缀
        String instruction = null;
        String cleanText = text;
        java.util.regex.Matcher m = INSTRUCTION_PREFIX.matcher(text);
        if (m.find()) {
            instruction = m.group(1).trim();
            cleanText = text.substring(m.end()).trim();
        }

        log.info("[TTS] 开始 TTS 语音回复 [{}]: {}，指令: {}",
                userId, cleanText.length() > 50 ? cleanText.substring(0, 50) + "..." : cleanText,
                instruction != null ? instruction : "无");

        try {
            byte[] audioData = audioProcessingService.synthesizeSpeech(cleanText, instruction);
            if (audioData == null || audioData.length == 0) {
                log.warn("[TTS] 语音合成返回空，降级为纯文本回复");
                sendTextSafe(userId, cleanText);
                return;
            }

            long ts = System.currentTimeMillis();
            int sizeKB = audioData.length / 1024;

            // 发送 WAV 文件（百炼 TTS 返回 PCM，已封装为 WAV）
            try {
                client.sendFile(userId, audioData, "voice_" + ts + ".wav",
                        String.valueOf(audioData.length));
                log.info("[TTS] WAV 文件发送成功 [{}]，大小: {}KB", userId, sizeKB);
                return;
            } catch (IOException e) {
                log.warn("[TTS] WAV 文件发送失败: {}", e.getMessage());
            }

            // 全部失败
            log.warn("[TTS] 语音文件发送失败，降级为纯文本回复");
            sendTextSafe(userId, cleanText);

        } catch (Exception e) {
            log.error("[TTS] 语音合成失败，降级为纯文本", e);
            sendTextSafe(userId, cleanText);
        }
    }

    // ==================== 基础发送工具 ====================

    private void sendTextSafe(String userId, String text) {
        try {
            client.sendText(userId, text);
            log.info("回复文本 [{}]: {}", userId, text.length() > 50 ? text.substring(0, 50) + "..." : text);
        } catch (IOException e) {
            log.error("发送文本失败 user={}", userId, e);
        }
    }

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
