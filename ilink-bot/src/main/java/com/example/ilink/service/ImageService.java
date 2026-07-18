package com.example.ilink.service;

import com.example.ilink.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class ImageService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public ImageService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public String vision(String userMessage, String base64Image) throws Exception {
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.API_BASE_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
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

    public byte[] generateImage(String prompt) throws Exception {
        System.out.println("[绘图] 开始生成: " + prompt);
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.DRAW_MODEL);
        body.addProperty("prompt", prompt);
        body.addProperty("image_size", "1024x1024");
        body.addProperty("batch_size", 1);
        body.addProperty("num_inference_steps", 20);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.DRAW_API_URL))
                .timeout(Config.REQ_TIMEOUT)
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            System.err.println("[绘图] API 错误: " + response.statusCode() + " " + response.body());
            return null;
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String imageUrl = json.getAsJsonArray("images").get(0).getAsJsonObject().get("url").getAsString();
        System.out.println("[绘图] 图片URL: " + imageUrl);

        HttpRequest downloadReq = HttpRequest.newBuilder()
                .uri(URI.create(imageUrl))
                .timeout(Config.REQ_TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

        HttpResponse<byte[]> imgResponse = httpClient.send(downloadReq, HttpResponse.BodyHandlers.ofByteArray());

        if (imgResponse.statusCode() == 200) {
            String contentType = imgResponse.headers().firstValue("Content-Type").orElse("");
            System.out.println("[绘图] 下载成功: " + imgResponse.body().length + " bytes, Content-Type: " + contentType);
            if (contentType.startsWith("image/") || contentType.startsWith("application/octet-stream")) {
                return imgResponse.body();
            }
            System.err.println("[绘图] 返回的不是图片: " + contentType);
            System.err.println("[绘图] 内容前200字节: " + new String(imgResponse.body(), 0, Math.min(200, imgResponse.body().length)));
            return null;
        }

        System.err.println("[绘图] 下载失败: " + imgResponse.statusCode());
        return null;
    }
}
