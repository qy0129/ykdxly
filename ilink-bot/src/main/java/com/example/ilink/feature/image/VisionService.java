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

public final class VisionService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public VisionService(HttpClient httpClient) {
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


}
