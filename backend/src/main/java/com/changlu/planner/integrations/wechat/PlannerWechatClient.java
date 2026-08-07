package com.changlu.planner.integrations.wechat;

import com.changlu.planner.shared.config.WebUrlResolver;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps WeChat transport concerns out of the planning command service. */
final class PlannerWechatClient {
  record AiReply(String text, List<String> imageUrls) {}
  record CommandReply(boolean handled, String message) {}
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(20);
  // 学习目标等重任务会触发多次模型调用（大纲 + 逐日分块展开），单次慢响应可能 100s+，
  // 学习子代理预算 480s：回环必须能容纳，否则 HTTP 先断导致 bot 报「AI 响应超时」。
  private static final Duration AI_TIMEOUT = Duration.ofSeconds(540);
  private static final Pattern DRAFT_ACTION = Pattern.compile("^(确认|取消)\\s*[:：]?\\s*([A-Za-z0-9-]{4,})$");
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
  private final Gson gson = new Gson();
  private final String apiBase = System.getenv().getOrDefault("PLANNER_API_BASE_URL", "http://127.0.0.1:8081/api").replaceAll("/$", "");

  /**
   * 快捷命令：解析 {handled, message} 契约。handled=false 时由上层回退 AI 对话，
   * 不能再复用 postText 的“已完成。”兜底，否则会掩盖命令未命中的事实。
   */
  CommandReply command(String userId, String message) throws Exception {
    JsonObject body = new JsonObject(); body.addProperty("text", message);
    HttpResponse<String> response = http.send(request("/integrations/wechat/command", userId)
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return new CommandReply(false, null);
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    boolean handled = result.has("handled") && result.get("handled").getAsBoolean();
    return new CommandReply(handled, handled && result.has("message") ? result.get("message").getAsString() : null);
  }

  String capture(String userId, String message) throws Exception { return postText("/integrations/wechat/capture", userId, message, "message"); }

  String briefing(String userId) throws Exception {
    JsonObject result = get("/integrations/wechat/briefing", userId);
    return result != null && result.has("message") ? result.get("message").getAsString() : "";
  }

  AiReply aiChat(String userId, String message) throws Exception {
    Matcher confirmation = DRAFT_ACTION.matcher(message == null ? "" : message.trim());
    if (confirmation.matches()) {
      return new AiReply(draftAction(userId, confirmation.group(2), confirmation.group(1).equals("确认") ? "confirm" : "cancel"), List.of());
    }

    JsonObject body = new JsonObject(); body.addProperty("message", message);
    HttpResponse<String> response = http.send(request("/integrations/wechat/ai", userId, AI_TIMEOUT)
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() / 100 != 2) return new AiReply("AI 暂时无法响应，请稍后再试。", List.of());
    JsonObject result = JsonParser.parseString(response.body()).getAsJsonObject();
    String reply = result.has("reply") ? result.get("reply").getAsString() : "我暂时没有生成回复。";
    List<String> imageUrls = imageUrls(result);
    if (!result.has("draft") || !result.get("draft").isJsonObject()) {
      // 信息搜集表（travel/learning 缺必填参数时返回 WAITING_USER + inputRequirements）：
      // 附工作台表单链接让用户点开填写，否则微信里只有一句"请先补充旅行信息"，找不到填表入口。
      if (result.has("inputRequirements") && result.get("inputRequirements").isJsonArray()
          && result.getAsJsonArray("inputRequirements").size() > 0) {
        String formTitle = result.has("formTitle") && !result.get("formTitle").isJsonNull()
            ? result.get("formTitle").getAsString() : "信息搜集表";
        return new AiReply(reply + "\n\n[点击填写" + formTitle + "](" + webUrl() + "#/agent)", imageUrls);
      }
      return new AiReply(reply, imageUrls);
    }

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
    // 复杂草案（旅游/学习计划等带卡片预览）始终附工作台链接，让用户可查看完整卡片。
    // 简单待办/日程草案（actions ≤ 3）不发链接，避免噪音。
    if (actions.size() > 3 || hasCardPreview(actions)) {
      preview.append("\n\n[点击查看完整卡片](").append(webUrl()).append("#/agent)");
    }
    return new AiReply(preview.toString(), imageUrls);
  }

  /** 草案里是否含卡片预览类动作（旅游行程、学习计划等），这类草案值得附工作台链接跳转看卡片。 */
  private boolean hasCardPreview(JsonArray actions) {
    for (int i = 0; i < actions.size(); i++) {
      JsonElement element = actions.get(i);
      if (!element.isJsonObject()) continue;
      JsonObject action = element.getAsJsonObject();
      String type = action.has("type") ? action.get("type").getAsString() : "";
      if ("create_travel_plan".equals(type) || "create_learning_plan".equals(type)
          || "create_plan".equals(type)) return true;
    }
    return false;
  }

  /** 从统一 Agent 返回中提取单张或批量图片 URL，微信层只负责传输。 */
  private List<String> imageUrls(JsonObject result) {
    List<String> urls = new ArrayList<>();
    addUrl(result, "imageUrl", urls);
    if (result.has("images") && result.get("images").isJsonArray()) {
      result.getAsJsonArray("images").forEach(item -> {
        if (item.isJsonObject()) addUrl(item.getAsJsonObject(), "imageUrl", urls);
      });
    }
    if (result.has("data") && result.get("data").isJsonObject()) {
      JsonObject data = result.getAsJsonObject("data");
      addUrl(data, "imageUrl", urls);
      if (data.has("images") && data.get("images").isJsonArray()) {
        data.getAsJsonArray("images").forEach(item -> {
          if (item.isJsonObject()) addUrl(item.getAsJsonObject(), "imageUrl", urls);
        });
      }
    }
    return urls.stream().distinct().toList();
  }

  private void addUrl(JsonObject object, String key, List<String> urls) {
    if (object.has(key) && !object.get(key).isJsonNull()) {
      String value = object.get(key).getAsString().trim();
      if (!value.isBlank()) urls.add(value);
    }
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
    return WebUrlResolver.resolve().replaceAll("/$", "");
  }
}
