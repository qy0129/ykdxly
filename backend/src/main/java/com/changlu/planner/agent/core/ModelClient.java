package com.changlu.planner.agent.core;

import com.changlu.planner.shared.config.EnvironmentConfig;
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
    return withRetries(purpose, maxAttempts, timeoutSeconds,
        attempt -> requestJson(purpose, messages, temperature, maxTokens, timeoutSeconds, attempt));
  }

  /** 返回模型原始文本（不解析 JSON），适合生成 Markdown 等非结构化输出。 */
  public String completeText(String purpose, JsonArray messages, double temperature, int maxTokens)
      throws Exception {
    return completeText(purpose, messages, temperature, maxTokens, 60, MAX_ATTEMPTS);
  }

  public String completeText(String purpose, JsonArray messages, double temperature, int maxTokens,
                             int timeoutSeconds, int maxAttempts) throws Exception {
    return withRetries(purpose, maxAttempts, timeoutSeconds,
        attempt -> requestRaw(purpose, messages, temperature, maxTokens, timeoutSeconds, attempt).text());
  }

  /** 统一超时与有限重试：把每次尝试封装成一个可抛异常的 Attempt，失败时按 retryable 判定重试。 */
  private <T> T withRetries(String purpose, int maxAttempts, int timeoutSeconds,
                            Attempt<T> attempt) throws Exception {
    if (!configured()) throw new IllegalStateException("PLANNER_AI_API_KEY 未配置");
    Exception lastError = null;
    for (int count = 1; count <= maxAttempts; count++) {
      try {
        return attempt.run(count);
      } catch (Exception error) {
        lastError = error;
        if (count == maxAttempts || !retryable(error)) throw error;
        LOG.warn("[模型重试] 第{}次，原因={}", count, error.getMessage());
        Thread.sleep(300L * count);
      }
    }
    throw lastError;
  }

  /** 发起一次模型请求并返回原始输出文本，供 JSON 或纯文本调用方各自解析。 */
  private RawReply requestRaw(String purpose, JsonArray messages, double temperature, int maxTokens,
                              int timeoutSeconds, int attempt) throws Exception {
    JsonObject body = new JsonObject();
    body.addProperty("model", model);
    body.addProperty("temperature", temperature);
    body.addProperty("max_tokens", maxTokens);
    body.addProperty("enable_thinking", false);
    // SiliconFlow 只接受单条 system 消息且必须位于开头：合并所有 system，避免 400。
    body.add("messages", normalizeMessages(messages));
    HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl))
        .timeout(Duration.ofSeconds(timeoutSeconds))
        .header("Authorization", "Bearer " + apiKey)
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body)))
        .build();
    long startedAt = System.nanoTime();
    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
    long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
    LOG.debug("[模型调用] 用途={} 状态={} 耗时={}毫秒", purpose, response.statusCode(), durationMs);
    if (response.statusCode() / 100 != 2) {
      // 带上响应体便于诊断（如 400 的 model/max_tokens/内容校验原因）。
      StringBuilder structure = new StringBuilder();
      for (JsonElement element : messages) {
        JsonObject m = element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        String role = m.has("role") ? m.get("role").getAsString() : "?";
        String content = m.has("content") ? m.get("content").getAsString() : "";
        structure.append(role).append('[').append(content.length()).append("字]");
        if (role.equals("system") && content.length() > 8) {
          structure.append('(').append(content.substring(0, Math.min(8, content.length()))).append("…)");
        }
        structure.append(' ');
      }
      LOG.warn("[模型调用失败] 用途={} 状态={} 消息结构={}", purpose, response.statusCode(), structure);
      throw new ModelHttpException(response.statusCode(),
          "AI 服务返回 " + response.statusCode() + "：" + preview(response.body()));
    }
    JsonObject choice = JsonParser.parseString(response.body()).getAsJsonObject()
        .getAsJsonArray("choices").get(0).getAsJsonObject();
    JsonObject message = choice.getAsJsonObject("message");
    String content = messageContent(message.get("content"));
    String reasoning = messageContent(message.get("reasoning_content"));
    if (reasoning.isBlank()) reasoning = messageContent(choice.get("reasoning_content"));
    if (reasoning.isBlank()) reasoning = messageContent(choice.get("text"));
    return new RawReply(content, reasoning);
  }

  /** 合并所有 system 消息为一条并置于开头（SiliconFlow 要求单条 system 且位于消息最前）。 */
  private JsonArray normalizeMessages(JsonArray messages) {
    StringBuilder system = new StringBuilder();
    JsonArray rest = new JsonArray();
    for (JsonElement element : messages) {
      if (element.isJsonObject() && "system".equals(element.getAsJsonObject().get("role").getAsString())) {
        JsonObject message = element.getAsJsonObject();
        String content = message.has("content") && !message.get("content").isJsonNull()
            ? message.get("content").getAsString() : "";
        if (!content.isBlank()) {
          if (system.length() > 0) system.append("\n\n");
          system.append(content);
        }
      } else {
        rest.add(element);
      }
    }
    if (system.length() == 0) return messages;
    JsonArray result = new JsonArray();
    result.add(ModelClient.message("system", system.toString()));
    for (JsonElement element : rest) result.add(element);
    return result;
  }

  private JsonObject requestJson(String purpose, JsonArray messages, double temperature, int maxTokens,
                                 int timeoutSeconds, int attempt) throws Exception {
    RawReply raw = requestRaw(purpose, messages, temperature, maxTokens, timeoutSeconds, attempt);
    String content = raw.content();
    String reasoning = raw.reasoning();
    JsonObject result = parseJsonObject(content);
    // 部分推理模型会把最终 JSON 放在 reasoning_content，content 只放解释文本。
    if (result == null && !reasoning.isBlank()) result = parseJsonObject(reasoning);
    if (result != null) return result;
    if (content.isBlank()) content = reasoning;
    LOG.warn("[模型 JSON 解析失败] 用途={} 内容预览={}", purpose, preview(content));
    throw new InvalidJsonException(content);
  }

  private JsonObject parseJsonObject(String content) {
    if (content == null || content.isBlank()) return null;
    JsonObject direct = tryParseObject(content.trim());
    if (direct != null) return direct;

    int thoughtEnd = content.lastIndexOf("</think>");
    String value = thoughtEnd >= 0
        ? content.substring(thoughtEnd + "</think>".length()).trim() : content.trim();
    value = value.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
    direct = tryParseObject(value);
    if (direct != null) return direct;

    JsonObject last = null;
    int start = -1;
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (start < 0) {
        if (current == '{') { start = index; depth = 1; }
        continue;
      }
      if (quoted) {
        if (escaped) escaped = false;
        else if (current == '\\') escaped = true;
        else if (current == '"') quoted = false;
        continue;
      }
      if (current == '"') quoted = true;
      else if (current == '{') depth++;
      else if (current == '}' && --depth == 0) {
        JsonObject parsed = tryParseObject(value.substring(start, index + 1));
        if (parsed != null) last = parsed;
        start = -1;
      }
    }
    return last;
  }

  /** 兼容模型返回字符串、文本块数组、思考标签和末尾逗号等常见格式偏差。 */
  private JsonObject tryParseObject(String value) {
    JsonObject parsed = parseStrictObject(value);
    if (parsed != null) return parsed;
    parsed = parseStrictObject(value.replaceAll(",\\s*([}\\]])", "$1"));
    if (parsed != null) return parsed;
    String normalized = normalizeJsonText(value);
    parsed = parseStrictObject(normalized);
    if (parsed != null) return parsed;
    parsed = parseStrictObject(normalized.replaceAll(",\\s*([}\\]])", "$1"));
    if (parsed != null) return parsed;
    // 有些兼容接口会把 JSON 作为转义字符串直接放进 content。
    String unescaped = normalized.replace("\\\"", "\"")
        .replace("\\n", " ").replace("\\r", " ").replace("\\t", " ");
    parsed = parseStrictObject(unescaped);
    if (parsed != null) return parsed;
    return parseStrictObject(unescaped.replaceAll(",\\s*([}\\]])", "$1"));
  }

  private JsonObject parseStrictObject(String value) {
    try {
      JsonElement parsed = JsonParser.parseString(value);
      if (parsed.isJsonObject()) return parsed.getAsJsonObject();
      if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString()) {
        JsonElement nested = JsonParser.parseString(parsed.getAsString());
        return nested.isJsonObject() ? nested.getAsJsonObject() : null;
      }
    } catch (Exception ignored) { }
    return null;
  }

  private String normalizeJsonText(String value) {
    StringBuilder normalized = new StringBuilder(value.length());
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      char normalizedCurrent = switch (current) {
        case '“', '”' -> '"';
        case '：' -> ':';
        default -> current;
      };
      if (quoted && (normalizedCurrent == '\n' || normalizedCurrent == '\r' || normalizedCurrent == '\t')) {
        // 保留换行转义，避免把 JSON 字符串里的真实换行压平成空格而破坏 Markdown。
        normalized.append('\\').append('n');
        continue;
      }
      if (escaped) escaped = false;
      else if (normalizedCurrent == '\\') escaped = true;
      else if (normalizedCurrent == '"') quoted = !quoted;
      normalized.append(normalizedCurrent);
    }
    return normalized.toString();
  }

  private String messageContent(JsonElement content) {
    if (content == null || content.isJsonNull()) return "";
    if (content.isJsonPrimitive()) return content.getAsString();
    if (!content.isJsonArray()) return content.toString();
    StringBuilder text = new StringBuilder();
    for (JsonElement part : content.getAsJsonArray()) {
      if (!part.isJsonObject()) continue;
      JsonElement value = part.getAsJsonObject().get("text");
      if (value != null && value.isJsonPrimitive()) text.append(value.getAsString());
    }
    return text.toString();
  }

  private String preview(String value) {
    String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
  }

  /** 单次模型尝试；允许抛出受检异常以便统一在 withRetries 里处理。 */
  @FunctionalInterface
  private interface Attempt<T> {
    T run(int attempt) throws Exception;
  }

  /** 一次模型请求的原始输出：content 为正文，reasoning 为思考文本。 */
  private record RawReply(String content, String reasoning) {
    /** 纯文本场景：content 优先，为空时退回 reasoning。 */
    String text() { return content.isBlank() ? reasoning : content; }
  }

  /** 保留模型原文，供复盘在结构化输出失败时展示真实 AI 总结。 */
  public static final class InvalidJsonException extends IllegalStateException {
    private final String content;

    public InvalidJsonException(String content) {
      super("AI 未返回有效 JSON，请重试或检查模型输出格式");
      this.content = content == null ? "" : content;
    }

    public String content() { return content; }
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
