package com.example.ilink.service;

import com.example.ilink.Config;
import com.example.ilink.HistoryManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class AiService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final HistoryManager history;

    public AiService(HttpClient httpClient, HistoryManager history) {
        this.httpClient = httpClient;
        this.history = history;
    }

    public String chat(String userId, String userMessage) throws Exception {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.MODEL);

            JsonArray messages = new JsonArray();

            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            String basePrompt = "你是一个专业的AI助手，回答问题准确简洁。";
            String personaPrompt = history.getPersonaPrompt(userId);
            if (personaPrompt != null) {
                basePrompt += "\n\n" + personaPrompt;
            }
            sysMsg.addProperty("content", basePrompt);
            messages.add(sysMsg);

            history.addHistoryMessages(messages, userId);

            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", userMessage);
            messages.add(userMsg);
            body.add("messages", messages);

            String requestBody = gson.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "网络波动了，请再发一次～";
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = json.getAsJsonArray("choices")
                    .get(0).getAsJsonObject()
                    .getAsJsonObject("message")
                    .get("content").getAsString();

            history.add(userId, userMessage, content);

            return content;
        } catch (Exception e) {
            return "网络波动了，请再发一次～";
        }
    }

    public static boolean isDrawRequest(String text) {
        String t = text.toLowerCase();
        return t.contains("生成") && (t.contains("图片") || t.contains("照片"))
                || t.contains("绘制")
                || t.contains("画一个")
                || t.contains("画一只")
                || t.contains("画一张")
                || t.contains("画幅")
                || t.contains("画一下")
                || t.matches(".*画.*图.*")
                || t.startsWith("画");
    }

    public String[] drawPrompt(String userMessage) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);

        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject props = new JsonObject();

        JsonObject enPromptProp = new JsonObject();
        enPromptProp.addProperty("type", "string");
        props.add("en_prompt", enPromptProp);

        JsonObject cnDescProp = new JsonObject();
        cnDescProp.addProperty("type", "string");
        props.add("cn_description", cnDescProp);

        schema.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("en_prompt");
        required.add("cn_description");
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);

        JsonObject jsonSchema = new JsonObject();
        jsonSchema.addProperty("name", "draw_request");
        jsonSchema.addProperty("strict", true);
        jsonSchema.add("schema", schema);

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_schema");
        responseFormat.add("json_schema", jsonSchema);
        body.add("response_format", responseFormat);

        JsonArray messages = new JsonArray();

        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", "用户想画图。提取用户的绘画描述，翻译成英文prompt，并用中文描述画面。");
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
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

        if (response.statusCode() != 200) return null;

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        String content = json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();

        try {
            JsonObject result = JsonParser.parseString(content).getAsJsonObject();
            String enPrompt = result.get("en_prompt").getAsString();
            String cnDesc = result.get("cn_description").getAsString();
            return new String[]{enPrompt, cnDesc};
        } catch (Exception e) {
            return null;
        }
    }
}
