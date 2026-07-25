package com.example.ilink.feature.image;

import com.example.ilink.config.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * 图片理解服务。
 *
 * <p>将图片转换为 Base64 后提交视觉模型，返回模型对图片的文字分析结果。</p>
 */
public final class VisionService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    /** 创建视觉服务并注入 HTTP 客户端。 */
    public VisionService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    /** 将 Base64 图片和用户问题提交给视觉模型。 */
    public String vision(String userMessage, String base64Image) throws Exception {
        // 图片分析和普通聊天使用不同模型与消息结构，因此单独封装。
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.VISION_MODEL);

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

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", "你是一个专业的AI助手，回答问题准确简洁。");
        messages.add(sysMsg);

        messages.add(userMsg);
        body.add("messages", messages);

        Exception lastError = null;
        int attempts = Math.max(1, Config.VISION_MAX_ATTEMPTS);
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(Config.API_BASE_URL))
                        .timeout(Config.VISION_REQ_TIMEOUT)
                        .header("Authorization", "Bearer " + Config.API_KEY)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                        .build();
                HttpResponse<String> response = httpClient.send(
                        request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    lastError = new IllegalStateException(
                            "图片分析失败 (HTTP " + response.statusCode() + ")");
                    continue;
                }
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                return json.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
            } catch (Exception e) {
                lastError = e;
                System.err.println("[Vision] 第 " + attempt + "/" + attempts
                        + " 次请求失败: " + e.getMessage());
            }
        }
        throw lastError == null ? new IllegalStateException("图片分析失败") : lastError;
    }


}
