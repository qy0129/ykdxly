package com.changlu.planner.features.briefing;

import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Builds current reference materials for one schedule from web search and AI. */
public final class ScheduleMaterialService {
  private final Database database;
  private final WebSearchTool search = new WebSearchTool();
  private final Gson gson = new Gson();
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final String apiKey = EnvironmentConfig.value("PLANNER_AI_API_KEY", "api.key", "");
  private final String apiUrl = EnvironmentConfig.value("PLANNER_AI_API_URL", "api.url", "https://api.siliconflow.cn/v1/chat/completions");
  private final String model = EnvironmentConfig.value("PLANNER_AI_MODEL", "ai.model", "Qwen/Qwen3.5-9B");

  public ScheduleMaterialService(Database database) { this.database = database; }

  public JsonObject load(Database.Context context, UUID scheduleId, boolean refresh) throws Exception {
    ScheduleTopic topic = topic(context.workspaceId(), scheduleId);
    if (topic == null) throw new IllegalArgumentException("schedule_not_found");
    List<WebSearchTool.Result> news = search.search(webQuery(topic), 8, refresh);
    JsonArray materials = new JsonArray();
    materials.add(platformMaterial("哔哩哔哩", "哔哩哔哩：搜索相关视频和讲解", "打开哔哩哔哩搜索相关视频、方法和经验分享。", "https://search.bilibili.com/all?keyword=" + encode(topic.query()), "#b85f42"));
    materials.add(platformMaterial("小红书", "小红书：搜索相关攻略和经验", "打开小红书搜索相关攻略、经验分享和实践清单。", "https://www.xiaohongshu.com/search_result?keyword=" + encode(topic.query()), "#d77d55"));
    JsonObject knowledge = summarize(topic, news);
    JsonObject result = new JsonObject();
    result.addProperty("query", topic.query());
    result.add("materials", materials);
    result.add("keyPoints", knowledge.get("keyPoints"));
    result.addProperty("studyNote", knowledge.get("studyNote").getAsString());
    result.add("sections", knowledge.get("sections"));
    result.addProperty("aiGenerated", knowledge.get("aiGenerated").getAsBoolean());
    return result;
  }

  private String webQuery(ScheduleTopic topic) {
    return topic.query() + " 资料 方法 指南 攻略 经验";
  }

  private JsonObject platformMaterial(String source, String title, String summary, String url, String color) {
    JsonObject material = new JsonObject();
    material.addProperty("id", source + "-search");
    material.addProperty("kind", "platform");
    material.addProperty("source", source);
    material.addProperty("title", title);
    material.addProperty("summary", summary);
    material.addProperty("meta", "平台入口 · 可选");
    material.addProperty("url", url);
    material.addProperty("color", color);
    return material;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private ScheduleTopic topic(UUID workspaceId, UUID scheduleId) throws Exception {
    String sql = "SELECT s.title, p.title, st.title, t.title, t.description "
        + "FROM schedule_items s LEFT JOIN plans p ON p.id=s.plan_id "
        + "LEFT JOIN plan_stages st ON st.id=s.stage_id "
        + "LEFT JOIN plan_tasks t ON t.id=s.task_id "
        + "WHERE s.id=? AND s.workspace_id=? AND s.deleted_at IS NULL";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(scheduleId));
      p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        // 优先用任务描述里的当天具体主题（如"复习极限与连续理论（30分钟）…"→"极限与连续理论"），
        // 避免用泛化的阶段标题（如"第一阶段：基础夯实与核心概念"）拼出与当天内容无关的 B站/小红书搜索词。
        String dayTopic = extractDayTopic(rs.getString(5));
        if (!dayTopic.isBlank()) return new ScheduleTopic(dayTopic);
        List<String> parts = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
          String value = rs.getString(index);
          if (value != null && !value.isBlank()) parts.add(value.trim());
        }
        return new ScheduleTopic(String.join(" ", parts));
      }
    }
  }

  /** 从任务描述提取当天主题：截取到第一个括号前、去掉常见动作前缀。如"复习极限与连续理论（30分钟）…"→"极限与连续理论"。 */
  private String extractDayTopic(String description) {
    if (description == null || description.isBlank()) return "";
    String value = description;
    int fullParen = value.indexOf('（');
    int asciiParen = value.indexOf('(');
    int cut = fullParen >= 0 && (asciiParen < 0 || fullParen < asciiParen) ? fullParen : asciiParen;
    if (cut >= 0) value = value.substring(0, cut);
    value = value.replaceFirst("^(复习|完成|学习|预习|掌握|攻克|训练|巩固|强化|背诵|记忆|理解|练习|精读|整理|回顾|研究|复盘)+", "")
        .replaceAll("[+＋\\s]+$", "").trim();
    if (value.isBlank() || value.equals("当日内容") || value.equals("当日安排")) return "";
    return value.length() > 60 ? value.substring(0, 60) : value;
  }

  private JsonObject summarize(ScheduleTopic topic, List<WebSearchTool.Result> news) {
    JsonObject fallback = fallbackKnowledge(topic, news);
    if (apiKey.isBlank() || news.isEmpty()) return fallback;
    try {
      StringBuilder source = new StringBuilder();
      for (WebSearchTool.Result item : news) source.append("标题：").append(item.title()).append("\n摘要：")
          .append(item.summary()).append("\n链接：").append(item.url()).append("\n\n");
      JsonObject body = new JsonObject();
      body.addProperty("model", model);
      body.addProperty("temperature", 0.2);
      body.addProperty("max_tokens", 900);
      JsonArray messages = new JsonArray();
      JsonObject system = new JsonObject(); system.addProperty("role", "system");
      system.addProperty("content", "你是日程资料整理助手。只输出 JSON：{\"keyPoints\":[\"关键点\"],\"studyNote\":\"一句总建议\",\"sections\":[{\"title\":\"事项概览\",\"content\":\"详细说明\"}]}。关键点最多 4 条，sections 输出 3 至 5 个有标题的详细段落，必须基于提供的资料；资料不足时明确说明，不要编造链接。内容要适用于旅行、健康、工作、学习和生活安排，不要默认使用学习语境。");
      JsonObject user = new JsonObject(); user.addProperty("role", "user");
      user.addProperty("content", "日程主题：" + topic.query() + "\n\n资料：\n" + source);
      messages.add(system); messages.add(user); body.add("messages", messages);
      HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(30))
          .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() / 100 != 2) return fallback;
      String content = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0)
          .getAsJsonObject().getAsJsonObject("message").get("content").getAsString().trim()
          .replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
      JsonObject parsed = JsonParser.parseString(content).getAsJsonObject();
      JsonArray points = parsed.has("keyPoints") && parsed.get("keyPoints").isJsonArray() ? parsed.getAsJsonArray("keyPoints") : new JsonArray();
      JsonArray sections = parsed.has("sections") && parsed.get("sections").isJsonArray() ? parsed.getAsJsonArray("sections") : new JsonArray();
      if (points.isEmpty() || !validSections(sections)) return fallback;
      JsonObject result = new JsonObject();
      result.add("keyPoints", points);
      result.addProperty("studyNote", parsed.has("studyNote") ? parsed.get("studyNote").getAsString() : fallback.get("studyNote").getAsString());
      result.add("sections", sections);
      result.addProperty("aiGenerated", true);
      return result;
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private JsonObject fallbackKnowledge(ScheduleTopic topic, List<WebSearchTool.Result> news) {
    JsonArray points = new JsonArray();
    points.add("先通读资料标题和摘要，确认它们与“" + topic.query() + "”的关联。" );
    if (!news.isEmpty()) points.add("优先打开排名靠前的原文，记录关键结论、步骤和一个可验证的结果。" );
    points.add("完成后用自己的话复盘要点，并把仍然不清楚的问题写入本次小记。" );
    JsonArray sections = new JsonArray();
    sections.add(section("事项概览", "先明确“" + topic.query() + "”要达成的结果，再区分关键信息、执行方法和实际步骤。不要只记结论，也要说明它什么时候适用。"));
    sections.add(section("行动步骤", "先用一份可靠资料建立整体框架，再核对关键细节；执行过程中把结论、步骤和一个具体结果分别记录下来。"));
    sections.add(section("执行建议", "把今天的主题拆成一个 20 至 30 分钟的小步骤，完成后记录结果、遇到的问题和下一步行动。"));
    sections.add(section("资料使用提醒", news.isEmpty() ? "当前没有获取到联网资料，下面的平台入口可用于继续查找；联网恢复后点击“重新获取”更新整理结果。" : "联网资料只作为参考入口，优先交叉核对来源、发布时间和原文上下文，不要只依据搜索摘要下结论。"));
    JsonObject result = new JsonObject(); result.add("keyPoints", points); result.add("sections", sections);
    result.addProperty("studyNote", "这份整理先帮你建立框架，再把内容落到几个可执行的行动。" );
    result.addProperty("aiGenerated", false); return result;
  }

  private boolean validSections(JsonArray sections) {
    if (sections.size() < 3) return false;
    for (JsonElement element : sections) {
      if (!element.isJsonObject()) return false;
      JsonObject section = element.getAsJsonObject();
      if (!section.has("title") || !section.has("content")
          || section.get("title").getAsString().isBlank() || section.get("content").getAsString().isBlank()) return false;
    }
    return true;
  }

  private JsonObject section(String title, String content) {
    JsonObject section = new JsonObject();
    section.addProperty("title", title);
    section.addProperty("content", content);
    return section;
  }

  private record ScheduleTopic(String query) {}
}
