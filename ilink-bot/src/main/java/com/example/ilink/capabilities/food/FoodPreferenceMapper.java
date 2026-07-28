package com.example.ilink.capabilities.food;

import com.example.ilink.bootstrap.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;

/** 把口味、营养和体感描述转换为高德可搜索的餐品关键词。 */
public final class FoodPreferenceMapper {

    private static final int MAX_KEYWORDS = 4;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String apiBaseUrl;
    private final String model;
    private final Gson gson = new Gson();

    public FoodPreferenceMapper(HttpClient httpClient) {
        this(httpClient, Config.API_KEY, Config.API_BASE_URL, Config.ROUTER_MODEL);
    }

    FoodPreferenceMapper(HttpClient httpClient, String apiKey, String apiBaseUrl, String model) {
        this.httpClient = httpClient;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.apiBaseUrl = apiBaseUrl;
        this.model = model;
    }

    public List<String> mapKeywords(String description) {
        String value = description == null ? "" : description.trim();
        if (value.isBlank()) return List.of("美食");

        List<String> local = localKeywords(value);
        if (!local.isEmpty()) return local;

        String cleaned = cleanConcreteKeyword(value);
        if (!looksLikePreference(value)) return List.of(cleaned.isBlank() ? value : cleaned);

        List<String> modelKeywords = mapWithModel(value);
        return modelKeywords.isEmpty() ? List.of("家常菜", "面馆", "轻食") : modelKeywords;
    }

    private List<String> localKeywords(String text) {
        LinkedHashSet<String> keywords = new LinkedHashSet<>();
        if (matches(text, "清淡|少油|少盐|不油|不腻|低油|低盐")) {
            add(keywords, "粥", "汤面", "馄饨", "蒸菜");
        }
        if (matches(text, "暖胃|养胃|胃不舒服|容易消化|好消化|软烂")) {
            add(keywords, "粥", "炖汤", "馄饨", "面馆");
        }
        if (matches(text, "减脂|低脂|减肥|控卡|低卡|少热量")) {
            add(keywords, "轻食", "沙拉", "健身餐", "鸡胸肉");
        }
        if (matches(text, "增肌|高蛋白|蛋白质")) {
            add(keywords, "健身餐", "鸡胸肉", "牛肉", "轻食");
        }
        if (matches(text, "素食|吃素|全素|素菜")) {
            add(keywords, "素食", "素菜", "蔬菜", "轻食");
        }
        if (matches(text, "不辣|不要辣|不能吃辣|少辣|免辣")) {
            add(keywords, "粤菜", "粥", "汤面", "馄饨");
        } else if (matches(text, "想吃辣|辣一点|重口|麻辣|香辣")) {
            add(keywords, "川菜", "湘菜", "麻辣烫", "火锅");
        }
        if (matches(text, "甜食|甜口|想吃甜|甜一点")) {
            add(keywords, "甜品", "蛋糕", "面包", "奶茶");
        }
        if (matches(text, "早餐|早饭")) {
            add(keywords, "粥", "包子", "馄饨", "面馆");
        }
        if (matches(text, "夜宵|宵夜")) {
            add(keywords, "烧烤", "小龙虾", "炸鸡", "粥");
        }
        return keywords.stream().limit(MAX_KEYWORDS).toList();
    }

    private List<String> mapWithModel(String description) {
        if (apiKey.isBlank()) return List.of();
        try {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("temperature", 0.1);
            body.addProperty("enable_thinking", false);

            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", "把用户的抽象饮食偏好转换为高德地图可搜索的餐品或餐厅分类词。"
                    + "只输出 JSON 字符串数组，最多4项，例如[\"粥\",\"汤面\",\"馄饨\"]。"
                    + "关键词必须简短常见，不要输出解释、句子、地址或虚构店铺名。");
            messages.add(system);

            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", description);
            messages.add(user);
            body.add("messages", messages);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiBaseUrl))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                System.err.println("[附近美食] 口味映射失败，HTTP " + response.statusCode());
                return List.of();
            }
            String content = JsonParser.parseString(response.body()).getAsJsonObject()
                    .getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            return parseKeywords(content);
        } catch (Exception error) {
            System.err.println("[附近美食] 口味映射失败: " + error.getMessage());
            return List.of();
        }
    }

    private List<String> parseKeywords(String content) {
        if (content == null) return List.of();
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start < 0 || end <= start) return List.of();
        try {
            JsonArray values = JsonParser.parseString(content.substring(start, end + 1)).getAsJsonArray();
            LinkedHashSet<String> keywords = new LinkedHashSet<>();
            for (JsonElement element : values) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
                String keyword = element.getAsString().replaceAll("[，,。；;、\\r\\n]", "").trim();
                if (keyword.isBlank() || keyword.length() > 10
                        || matches(keyword, "推荐|什么|食物|东西|好吃")) continue;
                keywords.add(keyword);
                if (keywords.size() >= MAX_KEYWORDS) break;
            }
            return List.copyOf(keywords);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private String cleanConcreteKeyword(String value) {
        return value.replaceAll("(有什么|有啥)?推荐.*$", "")
                .replaceAll("^(我)?(想吃|想喝|想要|找点|来点|吃点)", "")
                .replaceAll("(一点|一些)?(的)?(食物|东西)$", "")
                .replaceAll("[，,。？?！!\\s]+$", "").trim();
    }

    private boolean looksLikePreference(String value) {
        return matches(value, "推荐|健康|温和|舒服|消化|口味|油|腻|咸|甜|辣|素|"
                + "热量|蛋白|碳水|胃|随便|不知道吃什么|适合");
    }

    private boolean matches(String text, String alternatives) {
        return text != null && text.matches(".*(?:" + alternatives + ").*");
    }

    private void add(LinkedHashSet<String> keywords, String... values) {
        for (String value : values) keywords.add(value);
    }
}
