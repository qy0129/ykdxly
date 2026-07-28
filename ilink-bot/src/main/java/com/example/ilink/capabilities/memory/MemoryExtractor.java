package com.example.ilink.capabilities.memory;

import com.example.ilink.bootstrap.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Pattern;

public final class MemoryExtractor {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
            ".*(密码|身份证|银行卡|信用卡|验证码|支付口令).*", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile("(?:我叫|我的名字是|名字叫)([^，。！？]{1,20})");
    private static final Pattern HOME_PATTERN = Pattern.compile("(?:我住在|我常住在|我的常住地是|我家在)([^，。！？]{2,40})");
    private static final Pattern WORK_PATTERN = Pattern.compile("(?:我在)([^，。！？]{2,40})(?:上班|工作)");
    private static final Pattern DIET_PATTERN = Pattern.compile(".*(?:不吃|忌口|过敏).*");
    private static final Pattern STABLE_PREFERENCE_PATTERN = Pattern.compile(".*(?:我平时|我一直|我通常).*(?:喜欢|偏好|习惯).*");
    private static final Pattern LONG_TERM_GOAL_PATTERN = Pattern.compile(".*(?:长期目标|今年的目标|我的目标是|准备|打算).*(?:考研|考|学|做|开发|创建|写).*");

    private final MemoryService memoryService;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public MemoryExtractor(MemoryService memoryService, HttpClient httpClient) {
        this.memoryService = memoryService;
        this.httpClient = httpClient;
    }

    public void extract(String userId, String message) {
        if (userId == null || userId.isBlank() || message == null || message.isBlank()
                || SENSITIVE_PATTERN.matcher(message).matches()) return;

        String text = message.trim();

        MemoryCandidate quickMatch = quickRuleMatch(userId, text);
        if (quickMatch != null) {
            memoryService.saveExtractedMemory(quickMatch);
            return;
        }

        MemoryCandidate llmResult = llmJudge(userId, text);
        if (llmResult != null) {
            memoryService.saveExtractedMemory(llmResult);
        }
    }

    private MemoryCandidate quickRuleMatch(String userId, String text) {
        var name = NAME_PATTERN.matcher(text);
        if (name.find()) {
            return new MemoryCandidate(userId, "profile", "user_name", name.group(1).trim(), 9, "MemoryExtractor");
        }
        var home = HOME_PATTERN.matcher(text);
        if (home.find()) {
            return new MemoryCandidate(userId, "location", "home_location", home.group(1).trim(), 8, "MemoryExtractor");
        }
        var work = WORK_PATTERN.matcher(text);
        if (work.find()) {
            return new MemoryCandidate(userId, "profile", "work_place", work.group(1).trim(), 7, "MemoryExtractor");
        }
        if (DIET_PATTERN.matcher(text).matches()) {
            return new MemoryCandidate(userId, "preference", stableKey("diet", text), text, 7, "MemoryExtractor");
        }
        if (STABLE_PREFERENCE_PATTERN.matcher(text).matches()) {
            return new MemoryCandidate(userId, "preference", stableKey("preference", text), text, 6, "MemoryExtractor");
        }
        if (LONG_TERM_GOAL_PATTERN.matcher(text).matches()) {
            return new MemoryCandidate(userId, "goal", stableKey("goal", text), text, 8, "MemoryExtractor");
        }
        return null;
    }

    private static String stableKey(String prefix, String content) {
        return prefix + "_" + Integer.toUnsignedString(content.toLowerCase(java.util.Locale.ROOT).hashCode(), 16);
    }

    private MemoryCandidate llmJudge(String userId, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", Config.MODEL);
        body.addProperty("temperature", 0.1);

        JsonArray messages = new JsonArray();

        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "你是一个记忆提取器。判断用户的消息是否包含值得长期保存的个人信息。\n"
                + "只保存：长期目标、身份信息、稳定偏好、长期项目、居住地、工作地、饮食习惯。\n"
                + "不保存：临时查询（天气、新闻、快递）、一次性任务、普通闲聊。\n"
                + "如果值得保存，返回JSON：{\"save\":true,\"type\":\"goal|preference|profile|location\",\"content\":\"摘要内容\",\"importance\":1-10}\n"
                + "如果不值得，返回：{\"save\":false}");
        messages.add(system);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", text);
        messages.add(user);

        body.add("messages", messages);

        try {
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

            return parseLlmResponse(userId, content);
        } catch (Exception e) {
            System.err.println("[MemoryExtractor] LLM 判断失败: " + e.getMessage());
            return null;
        }
    }

    private MemoryCandidate parseLlmResponse(String userId, String content) {
        try {
            JsonObject result = JsonParser.parseString(content).getAsJsonObject();
            if (!result.get("save").getAsBoolean()) return null;
            String type = result.get("type").getAsString();
            String extracted = result.get("content").getAsString();
            int importance = result.get("importance").getAsInt();
            String key = stableKey(type, extracted);
            return new MemoryCandidate(userId, type, key, extracted, importance, "MemoryExtractor");
        } catch (JsonSyntaxException | NullPointerException e) {
            return null;
        }
    }
}
