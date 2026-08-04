package com.changlu.planner.agent.core;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 统一封装模型请求、结构化输出解析、超时和有限重试。 */
public final class ModelClient {
  private static final Logger LOG = LoggerFactory.getLogger(ModelClient.class);
  private static final int MAX_ATTEMPTS = 2;

  private final Gson gson = new Gson();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", "");
  private final String apiUrl = EnvironmentConfig.value(
      "PLANNER_AI_API_URL", "api.url", "https://api.siliconflow.cn/v1/chat/completions");
  private final String model = EnvironmentConfig.value("PLANNER_AI_MODEL", "ai.model", "Qwen/Qwen3.5-9B");

  public boolean configured() { return !apiKey.isBlank(); }

  public JsonObject completeJson(String purpose, JsonArray messages, double temperature, int maxTokens)
      throws Exception {
    return completeJson(purpose, messages, temperature, maxTokens, 60, MAX_ATTEMPTS);
  }

  public JsonObject completeJson(String purpose, JsonArray messages, double temperature, int maxTokens,
                                 int timeoutSeconds, int maxAttempts) throws Exception {
    if (!configured()) throw new IllegalStateException("PLANNER_AI_API_KEY 未配置");
    Exception lastError = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return requestJson(purpose, messages, temperature, maxTokens, timeoutSeconds, attempt);
      } catch (Exception error) {
        lastError = error;
        if (attempt == maxAttempts || !retryable(error)) throw error;
        LOG.warn("[模型重试] 用途={} 次数={} 原因={}", purpose, attempt, error.getMessage());
        Thread.sleep(300L * attempt);
      }
    }
    throw lastError;
  }

  private JsonObject requestJson(String purpose, JsonArray messages, double temperature, int maxTokens,
                                 int timeoutSeconds, int attempt) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("temperature", temperature);
    body.addProperty("max_tokens", maxTokens);
    body.add("messages", messages);
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        .build();
    long startedAt = System.nanoTime();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.info("[模型调用] 用途={} 模型={} 次数={} 状态={} 耗时={}毫秒", purpose, model, attempt,
        response.statusCode(), durationMs);
    if (response.statusCode() / 100 != 2) {
      throw new ModelHttpException(response.statusCode(), "AI 服务返回 " + response.statusCode());
    }
    String content = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
        .getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim()
        .replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
    try {
      return JsonParser.parseString(content).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException("AI 未返回有效 JSON", error);
    }
  }

  private boolean retryable(Exception error) {
    return !(error instanceof IllegalStateException) || error instanceof ModelHttpException http
        && (http.status == 408 || http.status == 429 || http.status >= 500);
  }

  public static JsonObject message(String role, String content) {
    JsonObject value = new JsonObject();
    value.addProperty("role", role);
    value.addProperty("content", content);
    return value;
  }

  private static final class ModelHttpException extends IllegalStateException {
    private final int status;
    private ModelHttpException(int status, String message) { super(message); this.status = status; }
  }
}
