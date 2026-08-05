package com.changlu.planner.agent.subagents.image.tools;

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

/**
 * SiliconFlow 文生图 Provider，遵循 /v1/images/generations 接口。
 * 4xx 按稳定错误码归类且不重试；429/5xx 标记可重试并带退避。错误详情写入日志，不进入用户回复。
 */
public final class SiliconFlowImageGenerationProvider implements ImageGenerationProvider {
  private static final Logger LOG = LoggerFactory.getLogger(SiliconFlowImageGenerationProvider.class);
  private static final int TIMEOUT_SECONDS = 120;

  private final Gson gson = new Gson();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_IMAGE_API_KEY", "image.api.key",
      EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", ""));
  private final String apiUrl = EnvironmentConfig.value(
      "PLANNER_IMAGE_API_URL", "image.api.url", "https://api.siliconflow.cn/v1/images/generations");
  private final String model = EnvironmentConfig.value(
      "PLANNER_IMAGE_MODEL", "image.model", "Kwai-Kolors/Kolors");

  @Override public String name() { return "siliconflow"; }

  @Override public String generate(String prompt, String size, String style, int quality) throws Exception {
    if (apiKey.isBlank()) {
      throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "文生图服务未配置 API Key", true);
    }
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("prompt", prompt);
    body.addProperty("image_size", size);
    body.addProperty("num_inference_steps", stepsForQuality(quality));
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        .build();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      throw forStatus(response);
    }
    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
    JsonArray images = root.has("images") ? root.getAsJsonArray("images") : null;
    if (images == null || images.isEmpty()) {
      throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "文生图服务未返回图片", true);
    }
    JsonObject first = images.get(0).getAsJsonObject();
    if (!first.has("url") || first.get("url").isJsonNull()) {
      throw new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE", "文生图服务未返回图片 URL", true);
    }
    return first.get("url").getAsString();
  }

  private ImageGenerationException forStatus(HttpResponse<String> response) {
    String detail = safeDetail(response.body());
    return switch (response.statusCode()) {
      case 401, 403 -> new ImageGenerationException("AUTH_FAILED", "文生图服务鉴权失败或模型未授权", false);
      case 400, 404 -> new ImageGenerationException("INVALID_ARGUMENT", "文生图请求参数无效", false);
      case 429 -> new ImageGenerationException("RATE_LIMITED", "文生图服务限流", true);
      default -> new ImageGenerationException("EXTERNAL_SERVICE_UNAVAILABLE",
          "文生图服务不可用 HTTP " + response.statusCode(), true);
    };
  }

  private String safeDetail(String body) {
    try {
      JsonObject root = JsonParser.parseString(body).getAsJsonObject();
      if (root.has("message")) return root.get("message").getAsString();
      return body.length() <= 200 ? body : body.substring(0, 200);
    } catch (Exception ignored) {
      return body == null ? "" : (body.length() <= 200 ? body : body.substring(0, 200));
    }
  }

  private int stepsForQuality(int quality) {
    return switch (quality) {
      case 1 -> 10;
      case 3 -> 40;
      default -> 20;
    };
  }
}