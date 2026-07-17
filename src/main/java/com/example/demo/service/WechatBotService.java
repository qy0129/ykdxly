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

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    private static final int MAX_HISTORY = 10;

    private static final OkHttpClient httpClient = new OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build();

    private static final Pattern IMAGE_GEN_PATTERN = Pattern.compile(
            "^(画|生成|绘制|创作|制作).*(图片|插画|壁纸|海报|画|图像|图)" +
            "|.*生成.*(图片|图像|插画|壁纸)" +
            "|.*(画一张|画个|画幅|画一个|做个|做一张|生成一张)" +
            "|^(帮我|给我|请).*(画|生成|绘制|创作|制作).*(图片|插画|壁纸|海报|图)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern STICKER_PATTERN = Pattern.compile(
            "发表情包|来个表情|发个表情|表情包|发个\\[|来张表情|sticker|贴纸",
            Pattern.CASE_INSENSITIVE);



    private static final Pattern TONE_PATTERN = Pattern.compile(
            "说话|语气|口吻|风格|模式|切换|变成|^幽默$|^可爱$|^正式$|^专业$|^温柔$|^诗意$|^简洁$|^热情$|^朋友$",
            Pattern.CASE_INSENSITIVE);

    @Autowired
    private DashScopeService dashScopeService;

    private final Map<String, List<String[]>> conversationHistory = new ConcurrentHashMap<>();
    private final Map<String, String> userTone = new ConcurrentHashMap<>();

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

                    // 意图分析：判断用户想要生成图片还是普通对话
                    if (STICKER_PATTERN.matcher(text).find()) {
                        handleStickerRequest(userId, text);
                        continue;
                    }
                    if (IMAGE_GEN_PATTERN.matcher(text).matches()) {
                        handleTextToImage(userId, text);
                        continue;
                    }

                    String reply = getReply(userId, text);
                    sendReply(userId, reply);
                } else if (item.getImage_item() != null) {
                    log.info("收到来自 {} 的图片消息", userId);
                    System.out.println("\n[消息] 来自 " + userId + ": [图片]");
                    try {
                        byte[] imageBytes = client.downloadImageFromMessageItem(item);
                        log.info("图片下载完成，大小: {} bytes", imageBytes.length);
                        // 先判断是否是表情包/贴纸
                        String stickerCheck = dashScopeService.chatWithImage(
                            "这张图是表情包/贴纸/Sticker吗？请只回答\"是\"或\"否\"，不要解释。", imageBytes, "image.png");
                        boolean isSticker = stickerCheck != null && stickerCheck.contains("是");
                        String prompt = isSticker
                            ? "请描述这张表情包的内容、风格和文字（如有），然后以轻松幽默的语气回复"
                            : "请详细描述这张图片的内容、风格、构图和色彩等画像信息";
                        String reply = dashScopeService.chatWithImage(prompt, imageBytes, "image.png");
                        if (reply != null) {
                            log.info(isSticker ? "AI 识别表情包完成" : "AI 识别图片完成");
                            sendReply(userId, reply);
                            addToHistory(userId, "[用户发送了" + (isSticker ? "一张表情包" : "一张图片") + "]", "[" + (isSticker ? "表情包" : "图片") + "画像] " + reply);
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
                        String recognized = item.getVoice_item().getText();
                        if (recognized == null || recognized.isBlank()) {
                            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
                            log.info("语音下载完成，大小: {} bytes", voiceBytes.length);
                            recognized = dashScopeService.transcribeAudio(voiceBytes);
                        } else {
                            log.info("微信自带转写: {}", recognized);
                        }
                        if (recognized != null && !recognized.isBlank()) {
                            log.info("语音转写结果: {}", recognized);
                            System.out.println("[语音识别] " + recognized);
                            // 语音转写后走统一的消息处理（文生图检测 + 语气适配）
                            if (IMAGE_GEN_PATTERN.matcher(recognized).matches()) {
                                handleTextToImage(userId, recognized);
                            } else {
                                String reply = getReply(userId, recognized);
                                sendReply(userId, reply);
                            }
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
                            addToHistory(userId, "[用户发送了文件: " + fileName + "]", "[文件分析] " + reply);
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
                        String reply = dashScopeService.chatWithVideo(videoBytes, "video.mp4", "请详细描述这段视频的内容和画面风格");
                        if (reply != null && !reply.isBlank()) {
                            log.info("AI 视频识别完成");
                            sendReply(userId, reply);
                            addToHistory(userId, "[用户发送了视频]", "[视频分析] " + reply);
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

    /**
     * 意图分析 + 文生图：结合对话历史上下文生成更精准的图片
     */
    private void handleTextToImage(String userId, String text) {
        log.info("检测到文生图意图，结合历史上下文: {}", text);

        // 先用 LLM 分析用户意图，提取精确的图片描述（结合对话历史中的画像信息）
        String tone = userTone.get(userId);
        String refinedPrompt = dashScopeService.chat(
            "根据以下对话历史，提取用于 AI 绘画（万相文生图）的精准 prompt，"
            + "仅返回 prompt 本身，不要解释。请充分利用历史中提到的图片画像、风格等信息。\n\n"
            + "用户最新消息: " + text,
            conversationHistory.get(userId), tone);

        // 如果 LLM 返回了有效 prompt，用它生成；否则 fallback 到原始文本
        String finalPrompt = (refinedPrompt != null && !refinedPrompt.isBlank()
                && refinedPrompt.length() < 500)
                ? refinedPrompt : text;

        log.info("文生图最终 prompt: {}", finalPrompt);

        String imageUrl = dashScopeService.textToImage(finalPrompt);
        if (imageUrl != null) {
            byte[] imageBytes = downloadBytes(imageUrl);
            if (imageBytes != null && imageBytes.length > 0) {
                log.info("图片下载成功，大小: {} bytes", imageBytes.length);
                byte[] compressed = compressImage(imageBytes, 800, 500 * 1024);
                try {
                    client.sendImage(userId, compressed, "image.jpg", "");
                    log.info("图片已发送给用户: {}", userId);
                    addToHistory(userId, text, "[已生成图片，prompt: " + finalPrompt + "]");
                } catch (Exception e) {
                    log.error("sendImage失败", e);
                    try {
                        client.sendFile(userId, compressed, "image.jpg", "");
                        log.info("以文件方式发送图片成功: {}", userId);
                        addToHistory(userId, text, "[已生成图片，prompt: " + finalPrompt + "]");
                    } catch (Exception ex) {
                        log.error("sendFile也失败", ex);
                        sendReply(userId, "图片已生成，但发送失败：" + imageUrl);
                    }
                }
            } else {
                sendReply(userId, "图片生成失败，下载结果为空。");
            }
        } else {
            sendReply(userId, "图片生成失败，请稍后重试或换个描述。");
        }
    }

    /**
     * 处理表情包请求：通过万相生成表情包风格的图片并发送
     */
    private void handleStickerRequest(String userId, String text) {
        log.info("检测到表情包请求: {}", text);
        // 提取表情主题，默认"搞笑"
        String theme = text.replaceAll("发表情包|来个表情|发个表情|表情包|来张表情|sticker|贴纸", "").trim();
        if (theme.isBlank() || theme.length() > 20) theme = "搞笑";
        String prompt = "卡通表情包风格，" + theme + "，简洁夸张的表情和动作，明亮的色彩，白色背景，表情包构图，线条清晰";
        log.info("表情包生成 prompt: {}", prompt);
        String imageUrl = dashScopeService.textToImage(prompt);
        if (imageUrl != null) {
            byte[] imageBytes = downloadBytes(imageUrl);
            if (imageBytes != null && imageBytes.length > 0) {
                byte[] compressed = compressImage(imageBytes, 400, 300 * 1024);
                try {
                    client.sendImage(userId, compressed, "sticker.jpg", "");
                    log.info("表情包已发送: {}", userId);
                } catch (Exception e) {
                    log.error("发送表情包失败", e);
                    try {
                        client.sendFile(userId, compressed, "sticker.jpg", "");
                        log.info("以文件方式发送表情包成功: {}", userId);
                    } catch (Exception ex) {
                        log.error("表情包发送失败", ex);
                        sendReply(userId, "(≧▽≦) 表情包生成失败~");
                    }
                }
            } else {
                sendReply(userId, "(´･_･`) 表情包下载失败了");
            }
        } else {
            sendReply(userId, "（︶︿︶）表情包生成出错了");
        }
    }

    /**
     * 将消息对保存到对话历史，使多模态分析结果能被后续文字对话引用
     */
    private void addToHistory(String userId, String userMsg, String botReply) {
        List<String[]> history = conversationHistory.get(userId);
        if (history == null) {
            history = new ArrayList<>();
            conversationHistory.put(userId, history);
        }
        history.add(new String[]{userMsg, botReply});
        if (history.size() > MAX_HISTORY) {
            history.remove(0);
        }
    }

    private String detectTone(String text) {
        if (!TONE_PATTERN.matcher(text).find()) return null;
        if (text.contains("正式") || text.contains("严肃") || text.contains("官方") || text.contains("商务")) return "正式";
        if (text.contains("可爱") || text.contains("卖萌") || text.contains("萌萌")) return "可爱";
        if (text.contains("专业") || text.contains("学术") || text.contains("技术")) return "专业";
        if (text.contains("朋友") || text.contains("随意") || text.contains("放松")) return "朋友";
        if (text.contains("幽默") || text.contains("搞笑") || text.contains("有趣")) return "幽默";
        if (text.contains("温柔") || text.contains("暖心") || text.contains("善解人意")) return "温柔";
        if (text.contains("诗意") || text.contains("文艺") || text.contains("浪漫")) return "诗意";
        if (text.contains("简洁") || text.contains("简短") || text.contains("精炼")) return "简洁";
        if (text.contains("默认") || text.contains("正常") || text.contains("普通")) return "默认";
        if (text.contains("热情") || text.contains("活泼") || text.contains("开朗")) return "热情";
        return null;
    }

    private String getReply(String userId, String text) {
        if (text == null || text.isBlank()) return "请输入消息内容。";

        if (text.trim().equals("/clear")) {
            conversationHistory.remove(userId);
            userTone.remove(userId);
            return "会话历史已清除，我们可以重新开始对话了。";
        }

        // 检测语气切换请求
        String newTone = detectTone(text);
        if (newTone != null) {
            userTone.put(userId, newTone);
            log.info("语气切换: {} -> {}", userId, newTone);
            if (text.length() <= 4) {
                return "ok，已切换为" + newTone + "模式";
            }
        }

        List<String[]> history = conversationHistory.get(userId);
        String tone = userTone.get(userId);
        String reply = dashScopeService.chat(text, history, tone);

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

    /**
     * 将图片压缩到指定最大尺寸和文件大小以内，解决 CDN 上传 500 问题
     */
    private byte[] compressImage(byte[] imageBytes, int maxDimension, int maxBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return imageBytes;

            int w = img.getWidth(), h = img.getHeight();
            if (w <= maxDimension && h <= maxDimension && imageBytes.length <= maxBytes) {
                return imageBytes;
            }

            double scale = Math.min((double) maxDimension / w, (double) maxDimension / h);
            int newW = (int) (w * scale), newH = (int) (h * scale);

            Image scaled = img.getScaledInstance(newW, newH, Image.SCALE_SMOOTH);
            BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(scaled, 0, 0, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", baos);
            byte[] result = baos.toByteArray();

            log.info("图片压缩: {}x{} -> {}x{}, {} bytes -> {} bytes", w, h, newW, newH, imageBytes.length, result.length);
            return result;
        } catch (Exception e) {
            log.warn("图片压缩失败: {}", e.getMessage());
            return imageBytes;
        }
    }

    private byte[] downloadBytes(String url) {
        try {
            Request request = new Request.Builder().url(url).get().build();
            try (Response resp = httpClient.newCall(request).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    return resp.body().bytes();
                }
                log.warn("下载图片失败: status={}", resp.code());
            }
        } catch (Exception e) {
            log.warn("下载图片异常: {}", e.getMessage());
        }
        return null;
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
