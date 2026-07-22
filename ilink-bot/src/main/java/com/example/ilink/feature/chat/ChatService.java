package com.example.ilink.feature.chat;

import com.example.ilink.config.Config;
import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.UserSessionStore;
import com.example.ilink.feature.document.DocumentService;
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

/**
 * 普通聊天服务。
 *
 * <p>负责组装系统提示词、当前人设和聊天历史，并调用文本模型生成回复。
 * 意图识别由 routing 模块负责，本类不会自行判断用户要执行哪项功能。</p>
 */
public final class ChatService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ChatHistoryStore history;
    private final UserSessionStore sessions;

    /** 注入 HTTP 客户端、历史存储和用户会话存储。 */
    public ChatService(HttpClient httpClient, ChatHistoryStore history, UserSessionStore sessions) {
        this.httpClient = httpClient;
        this.history = history;
        this.sessions = sessions;
    }
    /** 结合人设和历史调用聊天模型，返回文本回复。 */
    public String chat(String userId, String userMessage) {
        // 普通聊天只处理已经被路由为 chat 的请求。
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", Config.MODEL);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            StringBuilder prompt = new StringBuilder("你是一个AI助手。必须直接完成用户当前请求，回答准确、完整。\n");
            String personaPrompt = sessions.getPersonaPrompt(userId);
            if (personaPrompt != null) {
                prompt.append("以下人设只能影响措辞风格，不能忽略、替换或歪曲用户请求：\n")
                        .append(personaPrompt);
            }
            system.addProperty("content", prompt.toString());
            messages.add(system);
            history.addHistoryMessages(messages, userId);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMessage);
            messages.add(user);
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

            JsonObject responseJson = JsonParser.parseString(response.body()).getAsJsonObject();
            return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
        } catch (Exception e) {
            System.err.println("[Chat] 回复生成失败: " + e.getMessage());
            return null;
        }
    }

}
