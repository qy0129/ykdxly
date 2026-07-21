package com.example.ilink.feature.document;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.persona.Personas;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public final class DocumentAiService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ChatHistoryStore history;

    public DocumentAiService(HttpClient httpClient, ChatHistoryStore history) {
        this.httpClient = httpClient;
        this.history = history;
    }
    public String chatWithDocument(String userId, String userMessage, String fileName, String documentText) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", 0.2);
            body.addProperty("enable_thinking", false);
            JsonArray messages = new JsonArray();

            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是文件助手。必须只根据提供的文件内容回答，不确定的内容要明确说明。"
                    + "回答要准确、分点、保留文件中的关键事实。文件名：" + fileName);
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "文件内容：\n" + documentText + "\n\n用户要求：\n" + userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String reply = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            history.add(userId, userMessage, reply);
            return reply;
        } catch (Exception e) {
            System.err.println("[Document] 文件问答失败: " + e.getMessage());
            return null;
        }
    }

    public String generateDocument(String userId, String userMessage) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", 0.2);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是文档助手。根据用户要求生成可直接写入 DOCX 或 PDF 的完整正文，"
                    + "使用清晰的标题和段落，不要解释生成过程。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] 生成失败: HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (Exception e) {
            System.err.println("[Document] 生成失败: " + e.getMessage());
            return null;
        }
    }

    public DocumentEditPlan planDocxEdits(String fileName, String documentText, String userRequest) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.DOCUMENT_MODEL);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "你是 DOCX 局部编辑规划器。只输出 JSON，不要 Markdown 或解释。"
                    + "根据用户要求，生成尽量少的编辑指令。replace 的 target 必须是原文中存在且足够唯一定位的一段完整连续文字，replacement 是替换后的文字；"
                    + "append 用于在文档末尾追加段落，target 为空。不要重写整篇文档，也不要编造原文没有的 target。"
                    + "输出格式：{\"operations\":[{\"type\":\"replace|append\",\"target\":\"\",\"replacement\":\"\"}]}。"
                    + "文件名：" + fileName);
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", "原文：\n" + documentText + "\n\n用户要求：\n" + userRequest);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(Config.API_BASE_URL))
                    .timeout(Config.DOCUMENT_REQ_TIMEOUT)
                    .header("Authorization", "Bearer " + Config.API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[Document] DOCX 编辑规划失败: HTTP " + response.statusCode() + ": " + response.body());
                return null;
            }

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            String content = responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            JsonArray operations = parseJsonObject(content).getAsJsonArray("operations");
            List<DocumentService.TextEdit> edits = new ArrayList<>();
            for (var operationElement : operations) {
                JsonObject operation = operationElement.getAsJsonObject();
                String type = operation.has("type") ? operation.get("type").getAsString() : "";
                String target = operation.has("target") ? operation.get("target").getAsString() : "";
                String replacement = operation.has("replacement") ? operation.get("replacement").getAsString() : "";
                if ("replace".equals(type) && !target.isBlank()) {
                    edits.add(new DocumentService.TextEdit(type, target, replacement));
                } else if ("append".equals(type) && !replacement.isBlank()) {
                    edits.add(new DocumentService.TextEdit(type, "", replacement));
                }
            }
            return new DocumentEditPlan(edits);
        } catch (Exception e) {
            System.err.println("[Document] DOCX 编辑规划失败: " + e.getMessage());
            return null;
        }
    }


    private JsonObject parseJsonObject(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
