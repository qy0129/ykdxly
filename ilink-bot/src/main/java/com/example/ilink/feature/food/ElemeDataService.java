package com.example.ilink.feature.food;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** 尝试从饿了么公开 H5 搜索响应中匹配平台门店 ID。 */
public final class ElemeDataService {

    private static final String SEARCH_API =
            "https://h5.ele.me/restapi/shopping/restaurants/search";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;

    public ElemeDataService(HttpClient client) {
        this.client = client;
    }

    public String findStoreId(String keyword, String longitude, String latitude) {
        try {
            String query = "keyword=" + encode(keyword)
                    + "&latitude=" + encode(latitude)
                    + "&longitude=" + encode(longitude)
                    + "&offset=0&limit=10&terminal=h5";
            HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH_API + "?" + query))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile")
                    .header("Referer", "https://h5.ele.me/")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return "";
            return findStoreId(response.body(), keyword);
        } catch (Exception ignored) {
            return "";
        }
    }

    static String findStoreId(String json, String keyword) {
        if (json == null || json.isBlank()) return "";
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray stores = stores(root);
            for (JsonElement element : stores) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                JsonObject store = item.has("restaurant") && item.get("restaurant").isJsonObject()
                        ? item.getAsJsonObject("restaurant") : item;
                String name = text(store, "name");
                String id = text(store, "id");
                if (!id.isBlank() && namesMatch(name, keyword)) return id;
            }
        } catch (Exception ignored) {
            return "";
        }
        return "";
    }

    private static JsonArray stores(JsonElement root) {
        if (root.isJsonArray()) return root.getAsJsonArray();
        if (!root.isJsonObject()) return new JsonArray();
        JsonObject object = root.getAsJsonObject();
        JsonElement stores = object.get("restaurant_with_foods");
        return stores != null && stores.isJsonArray() ? stores.getAsJsonArray() : new JsonArray();
    }

    private static String text(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static boolean namesMatch(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        return !a.isBlank() && !b.isBlank() && (a.contains(b) || b.contains(a));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase()
                .replaceAll("[\\s（）()·._-]", "");
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
