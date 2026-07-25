package com.example.ilink.feature.media;

import com.example.ilink.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Bangumi 动漫资料查询。 */
public final class BangumiService {

    private final HttpClient httpClient;

    public BangumiService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<MediaKnowledgeItem> search(String query, int limit) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("keyword", query);
        body.addProperty("sort", "match");
        JsonObject filter = new JsonObject();
        JsonArray types = new JsonArray();
        types.add(2);
        filter.add("type", types);
        body.add("filter", filter);

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(Config.BANGUMI_API_BASE + "/v0/search/subjects"))
                .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                .header("Content-Type", "application/json")
                .header("User-Agent", Config.MUSICBRAINZ_USER_AGENT)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Bangumi HTTP " + response.statusCode());

        return parseResponse(response.body(), limit);
    }

    List<MediaKnowledgeItem> parseResponse(String responseBody, int limit) {
        JsonArray data = JsonParser.parseString(responseBody).getAsJsonObject().getAsJsonArray("data");
        if (data == null) return List.of();
        List<MediaKnowledgeItem> results = new ArrayList<>();
        for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name");
            String chineseName = string(item, "name_cn");
            String title = chineseName.isBlank() ? name : chineseName;
            if (title.isBlank()) continue;
            String date = string(item, "date");
            String score = number(item, "score");
            String detail = join(date, score.isBlank() ? "" : "评分 " + score);
            results.add(new MediaKnowledgeItem(title, detail, shorten(string(item, "summary"), 260),
                    "Bangumi", title + " 动漫"));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private String number(JsonObject object, String name) {
        try {
            double value = object.get(name).getAsDouble();
            return value <= 0 ? "" : String.format("%.1f", value);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private String join(String left, String right) {
        if (left.isBlank()) return right;
        return right.isBlank() ? left : left + "｜" + right;
    }

    private String shorten(String value, int limit) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
