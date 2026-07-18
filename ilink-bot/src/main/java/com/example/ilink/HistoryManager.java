package com.example.ilink;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HistoryManager {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final Map<String, List<JsonObject>> chatHistory = new ConcurrentHashMap<>();
    private final Map<String, String> conversationSummary = new ConcurrentHashMap<>();
    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY = 20;
    private static final int COMPRESS_BATCH = 6;

    public static final Map<String, String> PRESET_PERSONAS = new LinkedHashMap<>();
    static {
        PRESET_PERSONAS.put("小甜妹", "你是一个活泼可爱的小甜妹，喜欢用表情符号，语气俏皮温柔，每句话都带着甜甜的感觉，偶尔撒娇。");
        PRESET_PERSONAS.put("毒舌", "你是一个毒舌吐槽大师，说话尖酸刻薄但一针见血，爱用讽刺和反话，但本质上是在帮对方。");
        PRESET_PERSONAS.put("鲁迅", "模仿鲁迅的笔风和口吻，语言犀利深刻，爱用反讽和隐喻，常以'我向来是不惮以最坏的恶意来推测XX的'开头。");
        PRESET_PERSONAS.put("温柔女友", "你是一个温柔体贴的女友，说话轻声细语，关心对方的每一个细节，经常嘘寒问暖，让人感到被爱。");
        PRESET_PERSONAS.put("小猫娘", "你是一只可爱的猫娘，说话带'喵'的口癖，傲娇又粘人，喜欢撒娇求摸头。");
    }

    public HistoryManager(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public void setPersona(String userId, String persona) {
        personas.put(userId, persona);
    }

    public String getPersonaPrompt(String userId) {
        String name = personas.get(userId);
        if (name == null) return null;
        String prompt = PRESET_PERSONAS.get(name);
        if (prompt != null) return prompt;
        return "请用以下风格说话: " + name;
    }

    public String getPersonaName(String userId) {
        return personas.get(userId);
    }

    public void add(String userId, String userContent, String assistantContent) {
        List<JsonObject> history = chatHistory.computeIfAbsent(userId, k -> Collections.synchronizedList(new LinkedList<>()));
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userContent);
        history.add(userMsg);
        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.addProperty("content", assistantContent);
        history.add(assistantMsg);
        if (history.size() >= MAX_HISTORY) {
            compress(userId);
        }
    }

    public void addHistoryMessages(JsonArray target, String userId) {
        String summary = conversationSummary.get(userId);
        if (summary != null && !summary.isEmpty()) {
            JsonObject summaryMsg = new JsonObject();
            summaryMsg.addProperty("role", "system");
            summaryMsg.addProperty("content", "以下是更早对话的摘要：\n" + summary);
            target.add(summaryMsg);
        }
        List<JsonObject> history = chatHistory.get(userId);
        if (history != null) {
            synchronized (history) {
                for (JsonObject msg : history) {
                    target.add(msg);
                }
            }
        }
    }

    private void compress(String userId) {
        List<JsonObject> history = chatHistory.get(userId);
        if (history == null || history.size() < COMPRESS_BATCH) return;

        StringBuilder toSummarize = new StringBuilder();
        synchronized (history) {
            for (int i = 0; i < COMPRESS_BATCH && !history.isEmpty(); i++) {
                JsonObject msg = history.removeFirst();
                String role = msg.get("role").getAsString();
                String content = msg.get("content").getAsString();
                toSummarize.append("user".equals(role) ? "用户: " : "助手: ").append(content).append("\n");
            }
        }

        try {
            String summary = callSummary(toSummarize.toString());
            conversationSummary.merge(userId, summary, (old, val) -> old + "\n" + val);
        } catch (Exception ignored) {}
    }

    private String callSummary(String conversationText) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);

        JsonArray messages = new JsonArray();
        JsonObject sysMsg = new JsonObject();
        sysMsg.addProperty("role", "system");
        sysMsg.addProperty("content", "用一句话概括以下对话的核心内容，保留人名、地名、偏好等关键信息：");
        messages.add(sysMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", conversationText);
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
        if (response.statusCode() != 200) return "";

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonArray("choices")
                .get(0).getAsJsonObject()
                .getAsJsonObject("message")
                .get("content").getAsString();
    }
}
