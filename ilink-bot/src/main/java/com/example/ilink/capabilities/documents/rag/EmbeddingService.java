package com.example.ilink.capabilities.documents.rag;

import com.example.ilink.bootstrap.Config;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Embedding adapter compatible with providers that require a single string input. */
public class EmbeddingService {

    private final HttpClient httpClient;
    private final Gson gson = new Gson();

    public EmbeddingService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    public List<Float> embed(String text) throws Exception {
        return requestVector(requestBody(Config.EMBEDDING_MODEL, text));
    }

    /** Keep the batch API for callers, while sending provider-compatible single inputs. */
    public List<List<Float>> embedBatch(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) return List.of();
        List<List<Float>> vectors = new ArrayList<>(texts.size());
        for (String text : texts) vectors.add(embed(text));
        return vectors;
    }

    static JsonObject requestBody(String model, String text) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("input", text == null ? "" : text);
        body.addProperty("encoding_format", "float");
        return body;
    }

    private List<Float> requestVector(JsonObject body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(Config.EMBEDDING_API_URL))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + Config.API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            System.err.println("[Embedding] API 失败: HTTP " + response.statusCode()
                    + ", body=" + response.body());
            throw new RuntimeException("Embedding API failed: HTTP " + response.statusCode());
        }

        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray data = json.getAsJsonArray("data");
        if (data == null || data.isEmpty()) throw new RuntimeException("Embedding API returned empty data");
        JsonArray values = data.get(0).getAsJsonObject().getAsJsonArray("embedding");
        List<Float> vector = new ArrayList<>(values.size());
        for (var value : values) vector.add(value.getAsFloat());
        return vector;
    }
}
