package com.example.ilink.feature.media;

import com.example.ilink.config.Config;
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

/** MusicBrainz 歌手、歌曲和专辑资料查询。 */
public final class MusicBrainzService {

    private final HttpClient httpClient;
    private long lastRequestAt;

    public MusicBrainzService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<MediaKnowledgeItem> search(String query, String requestText, int limit)
            throws IOException, InterruptedException {
        String entity = requestText != null && requestText.contains("专辑") ? "release-group"
                : requestText != null && requestText.matches(".*(歌手|艺人|是谁|介绍).*" ) ? "artist"
                : "recording";
        rateLimit();
        URI uri = URI.create(Config.MUSICBRAINZ_API_BASE + "/" + entity + "/?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&fmt=json&limit=" + limit);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(Config.WEB_SEARCH_TIMEOUT_SECONDS))
                .header("User-Agent", Config.MUSICBRAINZ_USER_AGENT)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("MusicBrainz HTTP " + response.statusCode());
        return parseResponse(response.body(), entity, limit);
    }

    List<MediaKnowledgeItem> parseResponse(String responseBody, String entity, int limit) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        return switch (entity) {
            case "artist" -> parseArtists(root.getAsJsonArray("artists"), limit);
            case "release-group" -> parseAlbums(root.getAsJsonArray("release-groups"), limit);
            default -> parseRecordings(root.getAsJsonArray("recordings"), limit);
        };
    }

    private synchronized void rateLimit() throws InterruptedException {
        long waitMillis = 1100L - (System.currentTimeMillis() - lastRequestAt);
        if (waitMillis > 0) Thread.sleep(waitMillis);
        lastRequestAt = System.currentTimeMillis();
    }

    private List<MediaKnowledgeItem> parseArtists(JsonArray array, int limit) {
        List<MediaKnowledgeItem> results = new ArrayList<>();
        for (JsonElement element : safe(array)) {
            JsonObject item = element.getAsJsonObject();
            String name = string(item, "name");
            if (name.isBlank()) continue;
            String detail = join(string(item, "type"), string(item, "country"));
            results.add(new MediaKnowledgeItem(name, detail, shorten(string(item, "disambiguation"), 180),
                    "MusicBrainz", name + " 歌曲"));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private List<MediaKnowledgeItem> parseAlbums(JsonArray array, int limit) {
        List<MediaKnowledgeItem> results = new ArrayList<>();
        for (JsonElement element : safe(array)) {
            JsonObject item = element.getAsJsonObject();
            String title = string(item, "title");
            if (title.isBlank()) continue;
            String artist = artistCredit(item.getAsJsonArray("artist-credit"));
            String detail = join(artist, string(item, "first-release-date"));
            results.add(new MediaKnowledgeItem(title, detail, "", "MusicBrainz",
                    joinForSearch(artist, title)));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private List<MediaKnowledgeItem> parseRecordings(JsonArray array, int limit) {
        List<MediaKnowledgeItem> results = new ArrayList<>();
        for (JsonElement element : safe(array)) {
            JsonObject item = element.getAsJsonObject();
            String title = string(item, "title");
            if (title.isBlank()) continue;
            String artist = artistCredit(item.getAsJsonArray("artist-credit"));
            String date = string(item, "first-release-date");
            results.add(new MediaKnowledgeItem(title, join(artist, date), "", "MusicBrainz",
                    joinForSearch(artist, title)));
            if (results.size() >= limit) break;
        }
        return List.copyOf(results);
    }

    private Iterable<JsonElement> safe(JsonArray array) {
        return array == null ? List.of() : array;
    }

    private String artistCredit(JsonArray credits) {
        if (credits == null) return "";
        List<String> names = new ArrayList<>();
        for (JsonElement element : credits) {
            JsonObject credit = element.getAsJsonObject();
            String name = string(credit, "name");
            if (!name.isBlank()) names.add(name);
        }
        return String.join("、", names);
    }

    private String string(JsonObject object, String name) {
        if (object == null) return "";
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString().trim();
    }

    private String join(String left, String right) {
        if (left.isBlank()) return right;
        return right.isBlank() ? left : left + "｜" + right;
    }

    private String joinForSearch(String artist, String title) {
        return (artist + " " + title).trim();
    }

    private String shorten(String value, int limit) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
