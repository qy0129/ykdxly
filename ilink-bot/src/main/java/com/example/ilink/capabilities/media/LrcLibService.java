package com.example.ilink.capabilities.media;

import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** LRCLIB 歌词查询。 */
public final class LrcLibService {

    private final HttpClient httpClient;

    public LrcLibService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<MediaKnowledgeItem> search(String query, int limit) throws IOException, InterruptedException {
        URI uri = URI.create(Config.LRCLIB_API_BASE + "/search?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                .header("User-Agent", Config.MUSICBRAINZ_USER_AGENT)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("LRCLIB HTTP " + response.statusCode());
        return parseResponse(response.body(), limit);
    }

    List<MediaKnowledgeItem> parseResponse(String responseBody, int limit) {
        JsonArray data = JsonParser.parseString(responseBody).getAsJsonArray();
        List<MediaKnowledgeItem> results = new ArrayList<>();
        for (JsonElement element : data) {
            JsonObject item = element.getAsJsonObject();
            String track = string(item, "trackName");
            String artist = string(item, "artistName");
            if (track.isBlank()) continue;
            String album = string(item, "albumName");
            String lyrics = string(item, "plainLyrics");
            if (lyrics.isBlank()) lyrics = "暂时只有时间轴歌词，没有纯文本歌词。";
            results.add(new MediaKnowledgeItem(track, join(artist, album), shorten(lyrics, 700),
                    "LRCLIB", (artist + " " + track).trim()));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private String string(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private String join(String left, String right) {
        if (left.isBlank()) return right;
        return right.isBlank() ? left : left + "｜" + right;
    }

    private String shorten(String value, int limit) {
        String text = value.replace("\r", "").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
