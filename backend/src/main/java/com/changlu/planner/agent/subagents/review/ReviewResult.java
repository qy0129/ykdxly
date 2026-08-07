package com.changlu.planner.agent.subagents.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;

/** 复盘的结构化结果，同时负责 AI 输出的 JSON 边界转换。 */
public record ReviewResult(
    String date,
    JsonObject facts,
    String summary,
    JsonArray highlights,
    JsonArray risks,
    JsonArray nextActions,
    String generatedAt,
    boolean aiGenerated
) {
  public static ReviewResult fromGenerated(JsonObject facts, JsonObject generated, String generatedAt) {
    String summary = text(generated, "summary", "").trim();
    if (summary.isBlank()) throw new IllegalStateException("AI 返回的复盘总结为空");
    // 部分模型会把真正的复盘对象再次序列化到 summary 字符串中。
    // 在统一出口拆开，避免嵌套 JSON 被当成用户可读总结保存。
    JsonObject nested = parseEmbeddedJson(summary);
    JsonObject content = nested != null && nested.has("summary") ? nested : generated;
    summary = text(content, "summary", summary).trim();
    if (summary.isBlank()) throw new IllegalStateException("AI summary is empty");
    return new ReviewResult(
        text(facts, "date", LocalDate.now().toString()),
        facts,
        sanitizeText(summary),
        sanitize(array(content, "highlights")),
        sanitize(array(content, "risks")),
        sanitize(array(content, "nextActions")),
        generatedAt,
        true);
  }

  /** 模型理解了复盘但没有遵守 JSON 格式时，保留真实 AI 原文，不生成固定替代文案。 */
  public static ReviewResult fromPlainText(JsonObject facts, String content, String generatedAt) {
    String raw = content == null ? "" : content.trim();
    JsonObject structured = parseEmbeddedJson(raw);
    if (structured != null && structured.has("summary")) {
      return fromGenerated(facts, structured, generatedAt);
    }
    String summary = sanitizeText(raw);
    if (summary.isBlank()) throw new IllegalStateException("AI 返回了空的复盘内容");
    return new ReviewResult(
        text(facts, "date", LocalDate.now().toString()), facts, summary,
        new JsonArray(), new JsonArray(), new JsonArray(), generatedAt, true);
  }

  private static JsonObject parseEmbeddedJson(String value) {
    String candidate = value.replace("```json", "").replace("```JSON", "").replace("```", "").trim();
    // 从混有解释文字的返回中提取最后一个完整 JSON 对象。
    int start = -1;
    int depth = 0;
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < candidate.length(); i++) {
      char current = candidate.charAt(i);
      if (start < 0) {
        if (current == '{') { start = i; depth = 1; }
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
        candidate = candidate.substring(start, i + 1);
        break;
      }
    }
    try { return JsonParser.parseString(candidate).getAsJsonObject(); }
    catch (RuntimeException ignored) {
      try {
        String repaired = repairDelimiters(candidate.replace("\\\"", "\""))
            .replaceAll(",\\s*([}\\]])", "$1");
        return JsonParser.parseString(repaired).getAsJsonObject();
      } catch (RuntimeException ignoredAgain) {
        try {
          // AI 偶尔会带尾逗号、控制字符或全角标点，宽松读取后仍按字段校验。
          String normalized = candidate
              .replace('\u201c', '"').replace('\u201d', '"')
              .replace('\uFF1A', ':');
          normalized = repairDelimiters(normalized);
          JsonReader reader = new JsonReader(new StringReader(normalized));
          reader.setLenient(true);
          return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException ignoredLenient) { return null; }
      }
    }
  }

  /**
   * 模型偶尔会漏掉数组或对象的结束括号。按字符串状态补齐括号，避免把整段
   * JSON 当作用户可见的摘要；这不会改变已经完整的 JSON。
   */
  private static String repairDelimiters(String value) {
    StringBuilder output = new StringBuilder(value.length() + 8);
    Deque<Character> expected = new ArrayDeque<>();
    boolean quoted = false;
    boolean escaped = false;
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (quoted) {
        output.append(current);
        if (escaped) escaped = false;
        else if (current == '\\') escaped = true;
        else if (current == '"') quoted = false;
        continue;
      }
      if (current == '"') {
        quoted = true;
        output.append(current);
      } else if (current == '{') {
        expected.push('}');
        output.append(current);
      } else if (current == '[') {
        expected.push(']');
        output.append(current);
      } else if (current == '}' || current == ']') {
        while (!expected.isEmpty() && expected.peek() != current) output.append(expected.pop());
        if (!expected.isEmpty()) expected.pop();
        output.append(current);
      } else {
        output.append(current);
      }
    }
    while (!expected.isEmpty()) output.append(expected.pop());
    return output.toString();
  }

  public static ReviewResult fromCache(JsonObject facts, String summary, JsonObject suggestions,
                                       String generatedAt) {
    return new ReviewResult(
        text(facts, "date", LocalDate.now().toString()),
        facts,
        sanitizeText(summary),
        sanitize(array(suggestions, "highlights")),
        sanitize(array(suggestions, "risks")),
        sanitize(array(suggestions, "nextActions")),
        generatedAt,
        suggestions.has("aiGenerated") && suggestions.get("aiGenerated").getAsBoolean());
  }

  public JsonObject suggestions() {
    JsonObject value = new JsonObject();
    value.add("highlights", highlights.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("nextActions", nextActions.deepCopy());
    value.addProperty("aiGenerated", aiGenerated);
    return value;
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("date", date);
    value.add("facts", facts.deepCopy());
    value.addProperty("summary", summary);
    value.add("highlights", highlights.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("nextActions", nextActions.deepCopy());
    value.addProperty("generatedAt", generatedAt);
    value.addProperty("aiGenerated", aiGenerated);
    return value;
  }

  /** 复盘内容必须是可读中文：统一清洗模型偶发的代码块/Markdown/JSON 残留后再落库与展示。 */
  static String sanitizeText(String value) {
    if (value == null) return "";
    String raw = value.trim();
    String text = raw
        .replaceAll("(?s)<think>.*?</think>", "")
        .replaceAll("```json", "")
        .replaceAll("```JSON", "")
        // 代码围栏（含语言标注）连同围栏内的内容一并去掉
        .replaceAll("(?s)```[a-zA-Z]*.*?(?:```|$)", "")
        .replaceAll("```", "")
        .replace("`", "")
        .trim();
    // 围栏剥掉后整段仍是 JSON 对象：抽 summary 作为可读正文
    JsonObject embedded = parseEmbeddedJson(text);
    if (embedded != null && embedded.has("summary")) {
      return sanitizeText(text(embedded, "summary", text));
    }
    // 清洗后若被完全删空（原文几乎全是代码），回退保留原文，避免页面出现空白总结。
    return text.isBlank() ? raw : text;
  }

  /** 数组项逐条清洗，只保留清洗后非空的字符串项。 */
  private static JsonArray sanitize(JsonArray values) {
    JsonArray cleaned = new JsonArray();
    if (values == null) return cleaned;
    for (JsonElement element : values) {
      if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
        String item = sanitizeText(element.getAsString());
        if (!item.isBlank()) cleaned.add(item);
      } else {
        cleaned.add(element.deepCopy());
      }
    }
    return cleaned;
  }

  private static JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray()
        ? object.getAsJsonArray(name).deepCopy() : new JsonArray();
  }

  private static String text(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }
}
