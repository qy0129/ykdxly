package com.changlu.planner.agent.subagents.document.rag;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 硅基流动兼容的 Embedding 调用封装。 */
final class EmbeddingClient {
  private final Gson gson = new Gson();
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", "");
  private final String apiUrl = EnvironmentConfig.value(
      "PLANNER_EMBEDDING_API_URL", "embedding.api.url", "https://api.siliconflow.cn/v1/embeddings");
  private final String model = EnvironmentConfig.value(
      "PLANNER_EMBEDDING_MODEL", "embedding.model", "BAAI/bge-large-zh-v1.5");

  boolean configured() { return !apiKey.isBlank(); }

  List<Float> embed(String text) throws Exception {
    if (!configured()) throw new IllegalStateException("PLANNER_AI_API_KEY 未配置");
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("input", text == null ? "" : text);
    body.addProperty("encoding_format", "float");
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(45))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8)).build();
    HttpResponse<String> response = http.send(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException("Embedding 服务返回 " + response.statusCode());
    }
    JsonArray values = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("data").get(0)
        .getAsJsonObject().getAsJsonArray("embedding");
    List<Float> vector = new ArrayList<>(values.size());
    values.forEach(item -> vector.add(item.getAsFloat()));
    return List.copyOf(vector);
  }
}
