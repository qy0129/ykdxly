package com.example.ilink.conversation;

import com.example.ilink.config.Config;
import com.example.ilink.storage.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话历史和摘要存储。
 *
 * <p>保存用户与机器人的最近消息，并在历史过长时调用模型压缩成摘要，
 * 以控制后续请求的上下文长度。</p>
 */
public final class ChatHistoryStore {

    private static final int MAX_HISTORY = 20;
    private static final int COMPRESS_BATCH = 6;

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Map<String, List<JsonObject>> chatHistory = new ConcurrentHashMap<>();
    private final Map<String, String> conversationSummary = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();

    /** 创建历史存储器，并注入用于摘要压缩的 HTTP 客户端。 */
    public ChatHistoryStore(HttpClient httpClient) {
        this.httpClient = httpClient;
    }
    /** 把图片、音频或文档等媒体事件加入对话历史。 */
    public void addMedia(String userId, String type, String path, String summary) {
        add(userId, "[用户发送了" + type + ": " + path + "]", summary);
    }

    /** 追加一轮用户消息和机器人回复。 */
    public void add(String userId, String userContent, String assistantContent) {
        ensureLoaded(userId);
        List<JsonObject> history = chatHistory.computeIfAbsent(userId, k -> Collections.synchronizedList(new LinkedList<>()));
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userContent);
        history.add(userMsg);
        JsonObject assistantMsg = new JsonObject();
        assistantMsg.addProperty("role", "assistant");
        assistantMsg.addProperty("content", assistantContent);
        history.add(assistantMsg);
        database.saveConversation(userId, userContent, assistantContent);
        if (history.size() >= MAX_HISTORY) {
            compress(userId);
        }
    }

    /** 将当前用户的摘要和最近消息复制到模型请求数组。 */
    public void addHistoryMessages(JsonArray target, String userId) {
        ensureLoaded(userId);
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

    /** 压缩过长的历史，保留最近消息并更新摘要。 */
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
            if (summary == null || summary.isBlank()) return;
            String mergedSummary = conversationSummary.merge(
                    userId, summary, (old, val) -> old + "\n" + val);
            database.saveSummaryAndDeleteOldest(userId, mergedSummary, COMPRESS_BATCH);
        } catch (Exception ignored) {}
    }

    /** 用户首次访问时从 MySQL 恢复摘要和最近消息。 */
    private void ensureLoaded(String userId) {
        if (!database.isAvailable() || !loadedUsers.add(userId)) return;

        String summary = database.loadConversationSummary(userId);
        if (summary != null && !summary.isBlank()) {
            conversationSummary.put(userId, summary);
        }

        List<MySqlStore.ChatEntry> storedMessages = database.loadRecentMessages(userId, MAX_HISTORY);
        if (storedMessages.isEmpty()) return;

        List<JsonObject> history = Collections.synchronizedList(new LinkedList<>());
        for (MySqlStore.ChatEntry storedMessage : storedMessages) {
            JsonObject message = new JsonObject();
            message.addProperty("role", storedMessage.role());
            message.addProperty("content", storedMessage.content());
            history.add(message);
        }
        chatHistory.put(userId, history);
    }

    /** 调用模型把旧消息整理成简短摘要。 */
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
