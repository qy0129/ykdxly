package com.wechatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

public class Main {
    private static final String OLLAMA_BASE_URL = System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://127.0.0.1:11434");
    private static final String OLLAMA_MODEL = System.getenv().getOrDefault("OLLAMA_MODEL", "gemma3:4b");
    private static final String CDN_BASE = "https://novac2c.cdn.weixin.qq.com/c2c";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("正在启动 iLink 机器人...");
        System.out.println("LLM: Ollama (" + OLLAMA_MODEL + ") — " + OLLAMA_BASE_URL);

        var ollama = new OllamaClient(OLLAMA_BASE_URL, OLLAMA_MODEL);
        if (!ollama.isAlive()) {
            System.out.println("⚠ Ollama 未运行！请先启动 Ollama");
        }
        System.out.println("支持: 文字对话 / 图片识别 / 图片生成");

        var config = ILinkConfig.builder()
                .connectTimeoutMs(15000).readTimeoutMs(15000).writeTimeoutMs(15000)
                .httpMaxRetries(3).retryBaseDelayMs(1000).retryMaxDelayMs(10000)
                .heartbeatEnabled(true).heartbeatIntervalMs(30000)
                .build();

        var imageGen = new ImageGenClient();
        var clientRef = new AtomicReference<ILinkClient>();

        clientRef.set(ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override public void onLoginSuccess(LoginContext ctx) {
                        System.out.println("登录成功！Bot ID: " + ctx.getBotId());
                    }
                    @Override public void onLoginFailure(Throwable t) {
                        System.err.println("登录失败: " + t.getMessage());
                    }
                })
                .onMessage(new OnMessageListener() {
                    @Override public void onMessages(List<WeixinMessage> msgs) {
                        var client = clientRef.get();
                        if (client == null) return;
                        for (var msg : msgs) {
                            try {
                                handleMessage(client, ollama, imageGen, msg);
                            } catch (Exception e) {
                                System.err.println("[处理消息失败] " + e.getMessage());
                                try { client.sendText(msg.getFrom_user_id(), "抱歉，处理消息时出了点问题。"); } catch (Exception ignored) {}
                            }
                        }
                    }
                })
                .build());

        var client = clientRef.get();

        System.out.println("正在登录...");
        try {
            var qrContent = client.executeLogin();
            System.out.println("\n请用微信扫描以下二维码登录：");
            System.out.println(qrContent);
            System.out.println("（可在浏览器打开后截图扫码）");
            var loginCtx = client.getLoginFuture().get();
            System.out.println("登录完成，botId = " + loginCtx.getBotId());
        } catch (Exception e) {
            System.err.println("登录失败: " + e.getMessage());
            client.close();
            return;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在关闭机器人..."); client.close();
        }));

        System.out.println("\n机器人已启动，等待消息...");
        Thread.currentThread().join();
    }

    private static void handleMessage(ILinkClient client, OllamaClient ollama, ImageGenClient imageGen, WeixinMessage msg) throws Exception {
        try { client.startTyping(msg.getFrom_user_id()); } catch (Exception ignored) {}

        var userId = msg.getFrom_user_id();
        var items = msg.getItem_list();
        if (items == null || items.isEmpty()) return;

        var firstItem = items.get(0);
        var contextToken = msg.getContext_token();

        if (firstItem.getText_item() != null) {
            var text = firstItem.getText_item().getText();
            if (text == null || text.isEmpty()) return;
            System.out.println("\n[收到消息] 来自 " + userId + ": " + text);

            if (isImageGenRequest(text)) {
                handleImageGen(client, imageGen, userId, text, contextToken);
                return;
            }

            var reply = getReply(ollama, text);
            System.out.println("[AI回复] " + reply);
            client.sendText(userId, reply);
            return;
        }

        if (firstItem.getImage_item() != null) {
            System.out.println("\n[收到图片] 来自 " + userId);
            try {
                var imageData = client.downloadImageFromMessageItem(firstItem);
                if (imageData == null) { client.sendText(userId, "图片下载失败。"); return; }
                var reply = ollama.chat("请描述这张图片的内容", imageData);
                System.out.println("[AI回复] " + reply);
                client.sendText(userId, reply);
            } catch (Exception e) {
                System.out.println("[图片处理失败] " + e.getMessage());
                client.sendText(userId, "图片处理失败。");
            }
            return;
        }

        // ── 语音消息 ──
        if (firstItem.getVoice_item() != null) {
            System.out.println("\n[收到语音] 来自 " + userId);
            var voiceItem = firstItem.getVoice_item();
            var voiceText = voiceItem.getText();
            if (voiceText != null && !voiceText.trim().isEmpty()) {
                System.out.println("[语音转文字] " + voiceText);
                var reply = getReply(ollama, voiceText);
                System.out.println("[AI回复] " + reply);
                client.sendText(userId, reply);
                return;
            }
            // WeChat 未提供转写 → 下载音频，用 Ollama whisper 转写
            client.sendText(userId, "收到语音，正在识别...");
            try {
                var voiceData = client.downloadVoiceFromMessageItem(firstItem);
                if (voiceData == null || voiceData.length == 0) {
                    client.sendText(userId, "语音下载失败。"); return;
                }
                var transcribed = ollama.transcribe(voiceData);
                if (transcribed == null || transcribed.trim().isEmpty()) {
                    client.sendText(userId, "语音识别失败。"); return;
                }
                System.out.println("[Whisper转写] " + transcribed);
                var reply = getReply(ollama, transcribed);
                System.out.println("[AI回复] " + reply);
                client.sendText(userId, reply);
            } catch (Exception e) {
                System.out.println("[语音处理失败] " + e.getMessage());
                client.sendText(userId, "语音处理失败。");
            }
            return;
        }
    }

    // ── 方案 A: 手动 getuploadurl + CDN 上传 ──

    private static void handleImageGen(ILinkClient client, ImageGenClient imageGen, String userId, String prompt, String contextToken) {
        try {
            var imageData = imageGen.generate(prompt);
            System.out.println("[图片生成] 完成: " + imageData.length + " bytes");

            var ctx = client.getLoginContext();
            if (ctx == null) { client.sendText(userId, "登录状态失效，请重启机器人。"); return; }

            var aesKey = randomBytes(16);
            var aesKeyHex = bytesToHex(aesKey);
            var cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"));
            var encrypted = cipher.doFinal(imageData);

            var authHeaders = new HashMap<String, String>();
            authHeaders.put("Content-Type", "application/json; charset=utf-8");
            authHeaders.put("AuthorizationType", "ilink_bot_token");
            authHeaders.put("Authorization", "Bearer " + ctx.getBotToken());
            authHeaders.put("X-WECHAT-UIN", randomWechatUin());

            var filekey = randomHex(16);
            var rawMd5 = md5Hex(imageData);
            var uploadReq = JSON.createObjectNode();
            uploadReq.put("filekey", filekey);
            uploadReq.put("media_type", 1);
            uploadReq.put("to_user_id", userId);
            uploadReq.put("rawsize", imageData.length);
            uploadReq.put("rawfilemd5", rawMd5);
            uploadReq.put("filesize", encrypted.length);
            uploadReq.put("no_need_thumb", true);
            uploadReq.put("aeskey", aesKeyHex);
            var baseInfo = uploadReq.putObject("base_info");
            baseInfo.put("channel_version", "1.0.0");

            var uploadUrl = ctx.getBaseUrl() + "/ilink/bot/getuploadurl";
            System.out.println("[getuploadurl] 请求: " + uploadReq.toString());
            var uploadResp = httpPost(uploadUrl, authHeaders, uploadReq.toString());
            System.out.println("[getuploadurl] 响应: " + uploadResp);

            var uploadRoot = JSON.readTree(uploadResp);
            int ret = uploadRoot.path("ret").asInt(-999);
            if (ret != 0 && ret != -999) {
                throw new RuntimeException("getuploadurl ret=" + ret + ", errmsg=" + uploadRoot.path("errmsg").asText());
            }
            var uploadParam = uploadRoot.path("upload_param").asText();
            if (uploadParam.isEmpty()) {
                throw new RuntimeException("empty upload_param (ret=" + ret + "), 完整响应: " + uploadResp);
            }

            var cdnUrl = CDN_BASE + "/upload?encrypted_query_param="
                + URLEncoder.encode(uploadParam, StandardCharsets.UTF_8)
                + "&filekey=" + URLEncoder.encode(filekey, StandardCharsets.UTF_8);
            System.out.println("[CDN上传] url=" + cdnUrl + " size=" + encrypted.length);

            var cdnReq = HttpRequest.newBuilder(URI.create(cdnUrl))
                .timeout(java.time.Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofByteArray(encrypted))
                .header("Content-Type", "application/octet-stream")
                .build();
            var cdnRes = HTTP.send(cdnReq, HttpResponse.BodyHandlers.ofString());
            System.out.println("[CDN上传] HTTP " + cdnRes.statusCode());
            cdnRes.headers().map().forEach((k, v) -> System.out.println("  " + k + ": " + String.join(", ", v)));

            if (cdnRes.statusCode() != 200) throw new RuntimeException("CDN upload failed: HTTP " + cdnRes.statusCode());
            var encryptedParam = cdnRes.headers().firstValue("x-encrypted-param")
                .orElseThrow(() -> new RuntimeException("missing x-encrypted-param"));

            var aesKeyB64 = Base64.getEncoder().encodeToString(aesKeyHex.getBytes(StandardCharsets.UTF_8));
            var media = JSON.createObjectNode();
            media.put("encrypt_query_param", encryptedParam);
            media.put("aes_key", aesKeyB64);
            media.put("encrypt_type", 1);

            var imageItem = JSON.createObjectNode();
            imageItem.set("media", media);
            imageItem.put("aeskey", aesKeyHex);
            imageItem.put("mid_size", encrypted.length);

            var item = JSON.createObjectNode();
            item.put("type", 2);
            item.set("image_item", imageItem);

            var msg = JSON.createObjectNode();
            msg.put("to_user_id", userId);
            msg.put("client_id", clientId());
            msg.put("context_token", contextToken);
            var items = msg.putArray("item_list");
            items.add(item);

            var body = JSON.createObjectNode();
            body.set("msg", msg);
            var info = body.putObject("base_info");
            info.put("channel_version", "1.0.0");

            var sendUrl = ctx.getBaseUrl() + "/ilink/bot/sendmessage";
            System.out.println("[sendmessage] 请求体: " + body.toString());
            var sendResp = httpPost(sendUrl, authHeaders, body.toString());
            System.out.println("[sendmessage] 响应: " + sendResp);
            System.out.println("[发送图片] 成功");

        } catch (Exception e) {
            System.out.println("[发送图片失败] " + e.getMessage());
            try { client.sendText(userId, "图片发送失败，请稍后重试。"); } catch (Exception ignored) {}
        }
    }

    // ── 方案 B: 直接用 SDK sendImage（如果方案 A 不行的话切到这个）──

    private static void handleImageGenAlt(ILinkClient client, ImageGenClient imageGen, String userId, String prompt) {
        try {
            var imageData = imageGen.generate(prompt);
            System.out.println("[图片生成] 完成: " + imageData.length + " bytes");
            client.sendImage(userId, imageData, "生成图片.jpg", "为你生成的图片：" + prompt);
            System.out.println("[发送图片] 成功");
        } catch (Exception e) {
            System.out.println("[发送图片失败] " + e.getMessage());
            try {
                var url = "https://image.pollinations.ai/prompt/"
                    + java.net.URLEncoder.encode(prompt + "，高质量，详细", StandardCharsets.UTF_8);
                client.sendText(userId, "图片生成失败，查看链接：" + url);
            } catch (Exception ignored) {}
        }
    }

    // ── HTTP 工具 ──

    private static String httpPost(String url, Map<String, String> headers, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(java.time.Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(builder::header);
        var client = HttpClient.newHttpClient();
        var res = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new RuntimeException("HTTP " + res.statusCode() + ": " + res.body());
        return res.body();
    }

    // ── 工具函数 ──

    private static String randomHex(int bytes) {
        var buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return bytesToHex(buf);
    }

    private static byte[] randomBytes(int n) {
        var buf = new byte[n];
        RANDOM.nextBytes(buf);
        return buf;
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b & 0xff));
        return sb.toString();
    }

    private static String md5Hex(byte[] data) throws Exception {
        var md = java.security.MessageDigest.getInstance("MD5");
        return bytesToHex(md.digest(data));
    }

    private static String randomWechatUin() {
        return Base64.getEncoder().encodeToString(String.valueOf(RANDOM.nextInt() & 0xffffffffL).getBytes(StandardCharsets.UTF_8));
    }

    private static String clientId() {
        return "ilink-bot:" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    // ── AI 对话 ──

    private static String getReply(OllamaClient ollama, String text) {
        try {
            var reply = ollama.chat(text);
            if (reply != null && !reply.isEmpty()) return reply;
        } catch (Exception e) {
            System.out.println("Ollama 调用失败: " + e.getMessage());
        }
        var t = text.trim().toLowerCase();
        if (t.contains("你好") || t.contains("hello")) return "你好！我是 iLink 机器人，很高兴为你服务！";
        if (t.contains("你是谁")) return "我是基于微信官方 iLink 协议开发的 AI 机器人助手。";
        return "收到你的消息了：" + text;
    }

    private static boolean isImageGenRequest(String text) {
        var lower = text.toLowerCase();
        return lower.contains("画") || lower.contains("生成图片") || lower.contains("绘制")
                || lower.contains("创造图片") || lower.contains("画一张") || lower.contains("生成一张")
                || lower.contains("create image") || lower.contains("draw");
    }
}
