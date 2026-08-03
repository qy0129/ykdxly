package com.changlu.planner.integrations.wechat;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps WeChat transport concerns out of the planning command service. */
final class PlannerWechatClient {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
  private static final Duration AI_TIMEOUT = Duration.ofSeconds(70);
  private static final Pattern DRAFT_ACTION = Pattern.compile("^(确认|取消)\\s*[:：]?\\s*([A-Za-z0-9-]{4,})$");
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
    Matcher confirmation = DRAFT_ACTION.matcher(message == null ? "" : message.trim());
    if (confirmation.matches()) return draftAction(userId, confirmation.group(2), confirmation.group(1).equals("确认") ? "confirm" : "cancel");

    JsonObject body = new JsonObject(); body.addProperty("message", message);
    HttpResponse<String> response = http.send(request("/integrations/wechat/ai", userId, AI_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return "AI 暂时无法响应，请稍后再试。";
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    String reply = result.has("reply") ? result.get("reply").getAsString() : "我暂时没有生成回复。";
    if (!result.has("draft") || !result.get("draft").isJsonObject()) return reply;

    JsonObject draft = result.getAsJsonObject("draft");
    JsonArray actions = draft.getAsJsonArray("actions");
    String code = draft.get("code").getAsString();
    StringBuilder preview = new StringBuilder(reply).append("\n\n待确认草案（").append(actions.size()).append(" 项）：");
    for (int i = 0; i < actions.size(); i++) {
      JsonObject action = actions.get(i).getAsJsonObject();
      preview.append("\n- ").append(action.has("summary") ? action.get("summary").getAsString() : action.get("type").getAsString());
    }
    preview.append("\n\n草案编号：").append(code)
        .append("\n回复“确认 ").append(code).append("”执行，回复“取消 ").append(code).append("”放弃。")
        .toString();
    if (actions.size() > 3) preview.append("\n\n[点击此链接](").append(webUrl()).append("?view=review&draft=").append(draft.get("id").getAsString()).append(")");
    return preview.toString();
  }

  private String draftAction(String userId, String code, String action) throws Exception {
    HttpResponse<String> response = http.send(request("/integrations/wechat/ai/" + code + "/" + action, userId)
        .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) {
      JsonObject error = JsonParser.parseString(response.body()).getAsJsonObject();
      return error.has("error") ? "操作失败：" + error.get("error").getAsString() : "草案暂时无法处理。";
    }
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    if ("cancel".equals(action)) return "已取消草案 " + code + "。";
    int count = result.has("executed") ? result.getAsJsonArray("executed").size() : 0;
    return "已确认并执行草案 " + code + "，共完成 " + count + " 项操作。";
  }

  private String postText(String path, String userId, String message, String field) throws Exception {
    JsonObject body = new JsonObject(); body.addProperty("text", message);
    HttpResponse<String> response = http.send(request(path, userId)
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return "长路计划暂时无法处理这条消息。";
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    return result.has(field) ? result.get(field).getAsString() : result.has("title") ? "已记录：" + result.get("title").getAsString() : "已完成。";
  }

  private JsonObject get(String path, String userId) throws Exception {
    HttpResponse<String> response = http.send(request(path, userId).GET().build(), HttpResponse.BodyHandlers.ofString());
    return response.statusCode() / 100 == 2 ? JsonParser.parseString(response.body()).getAsJsonObject() : null;
  }

  private HttpRequest.Builder request(String path, String userId) { return request(path, userId, DEFAULT_TIMEOUT); }
  private HttpRequest.Builder request(String path, String userId, Duration timeout) {
    return HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(timeout).header("Content-Type", "application/json")
        .header("X-Wechat-User-Id", userId == null ? "" : userId);
  }

  private String webUrl() {
    return EnvironmentConfig.value("PLANNER_WEB_URL", "web.url", "http://127.0.0.1:8081/").replaceAll("/$", "");
  }
}
