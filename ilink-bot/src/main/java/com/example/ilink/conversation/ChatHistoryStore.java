package com.example.ilink.conversation;

import com.example.ilink.config.Config;
import com.example.ilink.storage.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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

    /** 60 轮对话，每轮包含一条用户消息和一条助手回复。 */
    private static final int MAX_HISTORY = 120;
    private static final int COMPRESS_BATCH = 6;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final MySqlStore database;
    private final Map<String, List<JsonObject>> chatHistory = new ConcurrentHashMap<>();
    private final Map<String, String> conversationSummary = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();

    /** 创建历史存储器，并注入用于摘要压缩的 HTTP 客户端。 */
    public ChatHistoryStore(HttpClient httpClient) {
        this(httpClient, MySqlStore.getInstance());
    }

    ChatHistoryStore(HttpClient httpClient, MySqlStore database) {
        this.httpClient = httpClient;
        this.database = database;
    }
    /** 把图片、音频或文档等媒体事件加入对话历史。 */
    public void addMedia(String userId, String type, String path, String summary) {
        add(userId, "[用户发送了" + type + ": " + path + "]", summary);
    }

    /** 追加一轮用户消息和机器人回复，自动记录时间戳。 */
    public void add(String userId, String userContent, String assistantContent) {
        addUserMessage(userId, userContent);
        addAssistantMessage(userId, assistantContent);
    }

    public void addUserMessage(String userId, String content) {
        appendMessage(userId, "user", content);
    }

    public void addAssistantMessage(String userId, String content) {
        appendMessage(userId, "assistant", content);
    }

    private void appendMessage(String userId, String role, String content) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) return;
        ensureLoaded(userId);
        List<JsonObject> history = chatHistory.computeIfAbsent(
                userId, ignored -> Collections.synchronizedList(new LinkedList<>()));
        synchronized (history) {
            if (!history.isEmpty()) {
                JsonObject last = history.getLast();
                if (role.equals(last.get("role").getAsString())
                        && content.equals(last.get("content").getAsString())) return;
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", role);
            message.addProperty("content", content);
            message.addProperty("created_at", LocalDateTime.now().format(TIMESTAMP_FORMAT));
            history.add(message);
        }
        if (database != null) database.saveMessage(userId, role, content);
        if (history.size() >= MAX_HISTORY) compress(userId);
    }

    /** 将当前用户的摘要和最近消息复制到模型请求数组。 */
    public void addHistoryMessages(JsonArray target, String userId) {
        addHistoryMessages(target, userId, null);
    }

    /** 复制历史时可排除已经提前记录的当前用户消息，避免模型收到两份相同输入。 */
    public void addHistoryMessages(JsonArray target, String userId, String currentUserMessage) {
        ensureLoaded(userId);
        String summary = conversationSummary.get(userId);
        if (summary != null && !summary.isEmpty()) {
            String summaryContext = "以下是更早对话的摘要：\n" + summary;
            if (!target.isEmpty()
                    && "system".equals(target.get(0).getAsJsonObject().get("role").getAsString())) {
                JsonObject systemMessage = target.get(0).getAsJsonObject();
                String systemContent = systemMessage.get("content").getAsString();
                systemMessage.addProperty("content", systemContent + "\n\n" + summaryContext);
            } else {
                JsonArray existingMessages = target.deepCopy();
                while (!target.isEmpty()) target.remove(target.size() - 1);

                JsonObject summaryMessage = new JsonObject();
                summaryMessage.addProperty("role", "system");
                summaryMessage.addProperty("content", summaryContext);
                target.add(summaryMessage);
                target.addAll(existingMessages);
            }
        }
        List<JsonObject> history = chatHistory.get(userId);
        if (history != null) {
            synchronized (history) {
                for (int index = 0; index < history.size(); index++) {
                    JsonObject msg = history.get(index);
                    boolean currentInput = index == history.size() - 1
                            && currentUserMessage != null
                            && "user".equals(msg.get("role").getAsString())
                            && currentUserMessage.equals(msg.get("content").getAsString());
                    if (!currentInput) target.add(msg);
                }
            }
        }
    }

    /**
     * 按内容关键词查找用户消息的创建时间。
     *
     * @param userId       用户 ID
     * @param contentKeyword 要搜索的消息内容关键词
     * @return 第一条匹配的用户消息的创建时间，未找到时返回 null
     */
    public String findUserMessageTime(String userId, String contentKeyword) {
        List<JsonObject> history = chatHistory.get(userId);
        if (history == null) return null;
        synchronized (history) {
            for (JsonObject msg : history) {
                if (!"user".equals(msg.get("role").getAsString())) continue;
                String content = msg.get("content").getAsString();
                if (content.contains(contentKeyword)) {
                    JsonElement time = msg.get("created_at");
                    return time == null ? null : time.getAsString();
                }
            }
        }
        return null;
    }

    /** 返回最近一条机器人文字回复，供“重新发一遍”在进程重启后回退使用。 */
    public String lastAssistantMessage(String userId) {
        ensureLoaded(userId);
        List<JsonObject> history = chatHistory.get(userId);
        if (history == null) return "";
        synchronized (history) {
            for (int index = history.size() - 1; index >= 0; index--) {
                JsonObject message = history.get(index);
                if (!"assistant".equals(message.get("role").getAsString())) continue;
                JsonElement content = message.get("content");
                return content == null ? "" : content.getAsString();
            }
        }
        return "";
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
            if (database != null) database.saveSummaryAndDeleteOldest(userId, mergedSummary, COMPRESS_BATCH);
        } catch (Exception ignored) {}
    }

    /** 用户首次访问时从 MySQL 恢复摘要和最近消息。 */
    private void ensureLoaded(String userId) {
        if (database == null || !database.isAvailable() || !loadedUsers.add(userId)) return;

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
