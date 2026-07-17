package com.example.ilink;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.vosk.*;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * iLink 微信机器人 - 千问智能版
 *
 * 使用 SDK: wechat-ilink-sdk (io.github.lith0924:wechat-ilink-sdk:2.3.3)
 * AI: 通义千问 DashScope API (OpenAI 兼容模式)
 *
 * 环境变量:
 *   DASHSCOPE_API_KEY - 千问 API Key（必填）
 *   QWEN_API_BASE_URL - API 地址（可选，默认 DashScope）
 *
 * 工作流程:
 *   1. 获取二维码 → 微信扫码登录
 *   2. 长轮询接收用户消息
 *   3. 调用千问 API 智能回复
 */
public class ILinkBot {

    // ========== 配置 ==========
    // API Key（从配置文件读取，不要硬编码）
    private static final String API_KEY = loadApiKey();
    // API 地址（硅基流动，国内直连）
    private static final String API_BASE_URL = "https://api.siliconflow.cn/v1/chat/completions";
    // 文本模型（免费，当前推荐 Qwen3-8B）
    private static final String MODEL = "Qwen/Qwen3-8B";
    // 图片分析用的多模态模型
    private static final String VISION_MODEL = "Qwen/Qwen3-VL-32B-Instruct";
    // ==========================

    private static final String MODEL_DIR = "models";
    private static final String MODEL_NAME = "vosk-model-small-cn-0.22";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/" + MODEL_NAME + ".zip";

    private static final String WHISPER_API_URL = "https://api.siliconflow.cn/v1/audio/transcriptions";
    private static final String WHISPER_MODEL = "FunAudioLLM/SenseVoiceSmall";

    private static String loadApiKey() {
        try {
            Properties props = new Properties();
            Path path = Path.of("config.properties");
            if (Files.exists(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    props.load(in);
                    String key = props.getProperty("api.key");
                    if (key != null && !key.isBlank() && !key.contains("把你的key")) {
                        return key;
                    }
                }
            }
        } catch (Exception ignored) {}
        System.err.println("错误: 请创建 config.properties 文件，内容为: api.key=你的Key");
        System.err.println("参考 config.properties.example");
        System.exit(1);
        return null;
    }

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Gson gson = new Gson();
    private Model voskModel;
    private volatile boolean running = true;

    public void start() throws Exception {
        System.out.println("========================================");
        System.out.println("  iLink 微信机器人 - 千问智能版");
        System.out.println("  SDK: wechat-ilink-sdk v2.3.3");
        System.out.println("  AI: Qwen (" + MODEL + ")");
        System.out.println("========================================\n");

        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("错误: 请修改代码中的 API_KEY 为你自己的 Key");
            System.exit(1);
        }

        // 初始化 Vosk 离线语音模型
        initVoskModel();

        ILinkClient client = ILinkClient.builder()
                .onMessage(createMessageHandler())
                .build();

        // 第一步：获取二维码
        System.out.println("正在获取登录二维码...");
        String qrcodeImg = client.executeLogin(); // base64 图片数据
        String qrcodeParam = client.getQrcode();  // 二维码参数

        // 二维码可能是链接 URL 或图片 base64
        if (qrcodeImg.startsWith("http")) {
            System.out.println("请浏览器打开此链接扫码登录:");
            System.out.println(qrcodeImg + "\n");
        } else {
            String raw = qrcodeImg.replaceFirst("^data:image/[a-zA-Z]+;base64,", "");
            Files.write(Path.of("qrcode.png"), Base64.getDecoder().decode(raw));
            System.out.println("二维码已保存到 qrcode.png，请打开并用微信扫码登录\n");
        }

        // 第二步：等待扫码确认
        System.out.println("等待扫码中...");
        while (!client.isLoggedIn()) {
            Thread.sleep(4000);
            System.out.println("  状态: 等待扫码...");
        }

        System.out.println("登录成功！开始监听消息... (Ctrl+C 退出)\n");

        // 第三步：长轮询收消息
        while (running) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                if (messages != null && !messages.isEmpty()) {
                    for (WeixinMessage msg : messages) {
                        handleMessage(client, msg);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    System.err.println("轮询异常: " + e.getMessage());
                    Thread.sleep(5000);
                }
            }
        }

        client.close();
    }

    private OnMessageListener createMessageHandler() {
        return messages -> {
            // SDK 内部自动调 getUpdates 后回调此方法
        };
    }

    private void handleMessage(ILinkClient client, WeixinMessage msg) {
        try {
            String fromUser = msg.getFrom_user_id();
            List<com.github.wechat.ilink.sdk.core.model.MessageItem> items = msg.getItem_list();

            if (items == null || items.isEmpty()) return;

            com.github.wechat.ilink.sdk.core.model.MessageItem first = items.get(0);

            // 文本消息
            if (first.getText_item() != null) {
                String text = first.getText_item().getText();
                System.out.println("[" + fromUser + "] " + text);
                System.out.println("[Bot] 正在思考...");
                String reply = callQwen(text);
                System.out.println("[Bot] → " + reply);
                client.sendText(fromUser, reply);
                return;
            }

            // 图片消息 → 下载后用多模态模型分析
            if (first.getImage_item() != null) {
                System.out.println("[" + fromUser + "] [图片]");
                System.out.println("[Bot] 正在分析图片...");
                byte[] imgBytes = client.downloadImageFromMessageItem(first);
                String b64 = Base64.getEncoder().encodeToString(imgBytes);
                String reply = callQwenWithImage("请描述这张图片", b64);
                System.out.println("[Bot] → " + reply);
                client.sendText(fromUser, reply);
                return;
            }

            // 语音消息 → 优先用微信自带 ASR 结果，失败则走本地识别
            if (first.getVoice_item() != null) {
                System.out.println("[" + fromUser + "] [语音]");

                // 微信服务端已自动转写，直接取 text 字段
                String asrText = first.getVoice_item().getText();
                if (asrText != null && !asrText.isBlank()) {
                    System.out.println("[Bot] 微信 ASR: " + asrText);
                    String reply = callQwen(asrText);
                    System.out.println("[Bot] → " + reply);
                    client.sendText(fromUser, reply);
                    return;
                }

                // 微信未提供 ASR，走本地识别
                System.out.println("[Bot] 正在识别语音...");
                byte[] voiceBytes = client.downloadVoiceFromMessageItem(first);
                Integer sampleRate = first.getVoice_item().getSample_rate();
                String text = recognizeVoice(voiceBytes, sampleRate != null ? sampleRate : 16000);
                if (text != null && !text.isBlank()) {
                    System.out.println("[Bot] 识别结果: " + text);
                    String reply = callQwen(text);
                    System.out.println("[Bot] → " + reply);
                    client.sendText(fromUser, reply);
                } else {
                    System.out.println("[Bot] 语音识别失败");
                    client.sendText(fromUser, "语音识别失败，请重试");
                }
                return;
            }

            // 文件消息
            if (first.getFile_item() != null) {
                String fileName = first.getFile_item().getFile_name();
                System.out.println("[" + fromUser + "] [文件] " + fileName);
                byte[] fileBytes = client.downloadFileFromMessageItem(first);
                String saved = "file_" + System.currentTimeMillis() + "_" + (fileName != null ? fileName : "unknown");
                Files.write(Path.of(saved), fileBytes);
                System.out.println("[Bot] 文件已保存到 " + saved);
                client.sendText(fromUser, "收到文件: " + (fileName != null ? fileName : "未知文件"));
                return;
            }

            // 其他类型（视频等）
            System.out.println("[" + fromUser + "] [未知消息类型]");
            client.sendText(fromUser, "暂不支持此消息类型");
        } catch (Exception e) {
            System.err.println("处理消息异常: " + e.getMessage());
        }
    }

    private String callQwen(String userMessage) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);

        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);
        body.add("messages", messages);

        String requestBody = gson.toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return "AI 回复失败 (HTTP " + response.statusCode() + ")";
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

        return content;
    }

    private String callQwenWithImage(String userMessage, String base64Image) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", VISION_MODEL);

        JsonArray contentArr = new JsonArray();

        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", userMessage);
        contentArr.add(textPart);

        JsonObject imgPart = new JsonObject();
        imgPart.addProperty("type", "image_url");
        JsonObject imgUrl = new JsonObject();
        imgUrl.addProperty("url", "data:image/png;base64," + base64Image);
        imgPart.add("image_url", imgUrl);
        contentArr.add(imgPart);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.add("content", contentArr);

        JsonArray messages = new JsonArray();
        messages.add(userMsg);
        body.add("messages", messages);

        String requestBody = gson.toJson(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_BASE_URL))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            return "图片分析失败 (HTTP " + response.statusCode() + ")";
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }

    private String recognizeVoice(byte[] voiceBytes, int sampleRate) {
        System.out.println("[语音] 语音大小: " + voiceBytes.length + " bytes, 采样率: " + sampleRate);

        // 检查 ffmpeg
        boolean hasFfmpeg = checkFfmpeg();
        System.out.println("[语音] ffmpeg: " + (hasFfmpeg ? "可用" : "不可用"));

        if (hasFfmpeg) {
            try {
                Path tmpDir = Files.createTempDirectory("ilink_voice_");
                Path silkFile = tmpDir.resolve("voice.silk");
                Path wavFile = tmpDir.resolve("voice.wav");

                // 微信 SILK 缺少文件头，补上
                byte[] silkHeader = "#!SILK_V3".getBytes();
                byte[] silkWithHeader = Arrays.copyOf(silkHeader, silkHeader.length + voiceBytes.length);
                System.arraycopy(voiceBytes, 0, silkWithHeader, silkHeader.length, voiceBytes.length);
                Files.write(silkFile, silkWithHeader);

                ProcessBuilder pb = new ProcessBuilder(
                        "ffmpeg", "-y", "-f", "silk", "-i", silkFile.toAbsolutePath().toString(),
                        "-ar", "16000", "-ac", "1", "-sample_fmt", "s16",
                        wavFile.toAbsolutePath().toString()
                );
                pb.redirectErrorStream(true);
                Process ffmpeg = pb.start();
                String ffmpegLog = new String(ffmpeg.getInputStream().readAllBytes());
                ffmpeg.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                boolean converted = ffmpeg.exitValue() == 0 && Files.exists(wavFile) && Files.size(wavFile) > 44;
                System.out.println("[语音] ffmpeg 退出码: " + ffmpeg.exitValue() + ", 转换结果: " + (converted ? "成功" : "失败"));
                if (!converted) System.out.println("[语音] ffmpeg 日志: " + ffmpegLog);

                if (converted) {
                    byte[] wavBytes = Files.readAllBytes(wavFile);
                    System.out.println("[语音] WAV 大小: " + wavBytes.length + " bytes");

                    String whisperResult = recognizeWithWhisper(wavBytes);
                    if (whisperResult != null && !whisperResult.isBlank()) {
                        cleanup(tmpDir, silkFile, wavFile);
                        return whisperResult;
                    }
                    System.out.println("[语音] Whisper 无结果，回退 Vosk");

                    byte[] pcmData = Arrays.copyOfRange(wavBytes, 44, wavBytes.length);
                    String voskResult = decodeVosk(pcmData, 16000);
                    if (voskResult != null && !voskResult.isBlank()) {
                        cleanup(tmpDir, silkFile, wavFile);
                        return voskResult;
                    }
                    System.out.println("[语音] Vosk 也无结果");
                }

                cleanup(tmpDir, silkFile, wavFile);
            } catch (Exception e) {
                System.out.println("[语音] ffmpeg 异常: " + e.getMessage());
            }
        }

        // Whisper 直接识别原始音频
        System.out.println("[语音] 尝试 Whisper 直接识别原始音频...");
        String whisperRaw = recognizeWithWhisper(voiceBytes);
        if (whisperRaw != null && !whisperRaw.isBlank()) {
            return whisperRaw;
        }

        // Vosk 直接识别原始音频
        System.out.println("[语音] 尝试 Vosk 直接识别原始音频...");
        try {
            return decodeVosk(voiceBytes, sampleRate);
        } catch (Exception ignored) {}

        return null;
    }

    private boolean checkFfmpeg() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean ok = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0;
            if (ok) {
                String ver = new String(p.getInputStream().readAllBytes());
                System.out.println("[语音] ffmpeg 版本: " + ver.lines().findFirst().orElse(""));
            }
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    private void cleanup(Path dir, Path... files) {
        for (Path f : files) { try { Files.deleteIfExists(f); } catch (Exception ignored) {} }
        try { Files.delete(dir); } catch (Exception ignored) {}
    }

    private String recognizeWithWhisper(byte[] audioBytes) {
        try {
            String boundary = "----" + UUID.randomUUID().toString();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            baos.write(("--" + boundary + "\r\n").getBytes());
            baos.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes());
            baos.write((WHISPER_MODEL + "\r\n").getBytes());

            baos.write(("--" + boundary + "\r\n").getBytes());
            baos.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".getBytes());
            baos.write("Content-Type: audio/wav\r\n\r\n".getBytes());
            baos.write(audioBytes);
            baos.write("\r\n".getBytes());

            baos.write(("--" + boundary + "--\r\n").getBytes());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(WHISPER_API_URL))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String text = json.has("text") ? json.get("text").getAsString() : null;
                System.out.println("[Whisper] 识别成功: " + (text != null ? text.substring(0, Math.min(50, text.length())) : "null"));
                return text;
            } else {
                System.out.println("[Whisper] API 返回 " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            System.out.println("[Whisper] 异常: " + e.getMessage());
        }
        return null;
    }

    private String decodeVosk(byte[] pcmData, int sampleRate) {
        if (voskModel == null) {
            System.out.println("[调试] Vosk 模型未加载");
            return null;
        }
        try (Recognizer recognizer = new Recognizer(voskModel, (float) sampleRate)) {
            recognizer.acceptWaveForm(pcmData, pcmData.length);
            String result = recognizer.getResult();
            System.out.println("[调试] Vosk 原始结果: " + result);
            JsonObject json = JsonParser.parseString(result).getAsJsonObject();
            return json.has("text") ? json.get("text").getAsString() : null;
        } catch (Exception e) {
            System.err.println("Vosk 识别异常: " + e.getMessage());
            return null;
        }
    }

    private void initVoskModel() throws Exception {
        Path modelPath = Path.of(MODEL_DIR, MODEL_NAME);
        if (Files.exists(modelPath)) {
            loadVoskModel(modelPath);
            return;
        }

        Files.createDirectories(Path.of(MODEL_DIR));
        System.out.println("[语音] 语音模型未找到，开始自动下载...");
        try {
            downloadModel(Path.of(MODEL_DIR));
        } catch (Exception e) {
            System.err.println("[语音] 自动下载失败: " + e.getMessage());
            System.err.println("请手动下载并解压:");
            System.err.println("  " + MODEL_URL);
            System.err.println("解压到目录:");
            System.err.println("  " + modelPath.toAbsolutePath());
            return;
        }

        if (Files.exists(modelPath)) {
            loadVoskModel(modelPath);
        } else {
            System.err.println("[语音] 模型解压后未找到预期目录，请手动检查");
        }
    }

    private void downloadModel(Path targetDir) throws Exception {
        System.out.println("[语音] 正在下载 " + MODEL_NAME + " ...");
        URL url = new URL(MODEL_URL);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);

        byte[] zipBytes;
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            long total = 0;
            int fileSize = conn.getContentLength();
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                total += len;
                if (fileSize > 0) {
                    System.out.print("\r[语音] 下载中... " + (total * 100 / fileSize) + "%");
                }
            }
            System.out.println("\n[语音] 下载完成，正在解压...");
            zipBytes = out.toByteArray();
        }

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            byte[] buf = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    try (FileOutputStream fos = new FileOutputStream(entryPath.toFile())) {
                        int len;
                        while ((len = zis.read(buf)) != -1) {
                            fos.write(buf, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }

        System.out.println("[语音] 模型解压完成");
    }

    private void loadVoskModel(Path modelPath) {
        try {
            LibVosk.setLogLevel(LogLevel.WARNINGS);
            voskModel = new Model(modelPath.toAbsolutePath().toString());
        } catch (Exception e) {
            System.err.println("加载语音模型失败: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
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
