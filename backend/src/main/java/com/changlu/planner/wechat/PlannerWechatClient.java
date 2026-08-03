package com.changlu.planner.wechat;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** 微信 Bot 到长路计划 API 的最小适配器。 */
final class PlannerWechatClient {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration AI_TIMEOUT = Duration.ofSeconds(70);
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final Gson gson = new Gson();
  private final String apiBase = System.getenv().getOrDefault("PLANNER_API_BASE_URL", "http://127.0.0.1:8081/api").replaceAll("/$", "");

  String command(String userId, String message) throws Exception { return postText("/integrations/wechat/command", userId, message, "message"); }
  String capture(String userId, String message) throws Exception { return postText("/integrations/wechat/capture", userId, message, "message"); }

  String briefing(String userId) throws Exception {
    JsonObject result = get("/integrations/wechat/briefing", userId);
    return result != null && result.has("message") ? result.get("message").getAsString() : "";
  }

  String aiChat(String userId, String message) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("message", message);
    body.add("history", new JsonArray());
    HttpRequest request = request("/ai/review/chat", userId, AI_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return "AI 暂时无法响应，请稍后再试。";
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    return result.has("reply") ? result.get("reply").getAsString() : "我暂时没有生成回复。";
  }

  private String postText(String path, String userId, String message, String field) throws Exception {
    JsonObject body = new JsonObject(); body.addProperty("text", message);
    HttpRequest request = request(path, userId).POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return "长路计划暂时无法处理这条消息。";
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    return result.has(field) ? result.get(field).getAsString() : result.has("title") ? "已记录：" + result.get("title").getAsString() : "已完成。";
  }

  private JsonObject get(String path, String userId) throws Exception {
    HttpResponse<String> response = http.send(request(path, userId).GET().build(), HttpResponse.BodyHandlers.ofString());
    return response.statusCode() / 100 == 2 ? JsonParser.parseString(response.body()).getAsJsonObject() : null;
  }

  private HttpRequest.Builder request(String path, String userId) {
    return request(path, userId, DEFAULT_TIMEOUT);
  }

  private HttpRequest.Builder request(String path, String userId, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(timeout).header("Content-Type", "application/json").header("X-Wechat-User-Id", userId == null ? "" : userId);
  }
}
