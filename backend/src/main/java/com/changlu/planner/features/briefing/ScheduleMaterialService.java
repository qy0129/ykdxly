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

/** Builds current learning materials for one schedule from web search and AI. */
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
    materials.add(platformMaterial("哔哩哔哩", "哔哩哔哩：搜索相关课程和讲解视频", "打开哔哩哔哩搜索相关课程、训练方法和讲解视频。", "https://search.bilibili.com/all?keyword=" + encode(topic.query()), "#b85f42"));
    materials.add(platformMaterial("小红书", "小红书：搜索相关学习笔记和经验分享", "打开小红书搜索相关学习笔记、经验分享和实践清单。", "https://www.xiaohongshu.com/search_result?keyword=" + encode(topic.query()), "#d77d55"));
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
    return topic.query() + " 学习资料 方法 指南 经验";
  }

  private JsonObject platformMaterial(String source, String title, String summary, String url, String color) {
    JsonObject material = new JsonObject();
    material.addProperty("id", source + "-search");
    material.addProperty("kind", "platform");
    material.addProperty("source", source);
    material.addProperty("title", title);
    material.addProperty("summary", summary);
    material.addProperty("meta", "平台入口 · 必备");
    material.addProperty("url", url);
    material.addProperty("color", color);
    return material;
  }

  private String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private ScheduleTopic topic(UUID workspaceId, UUID scheduleId) throws Exception {
    String sql = "SELECT s.title, p.title, st.title, t.title "
        + "FROM schedule_items s LEFT JOIN plans p ON p.id=s.plan_id "
        + "LEFT JOIN plan_stages st ON st.id=s.stage_id "
        + "LEFT JOIN plan_tasks t ON t.id=s.task_id "
        + "WHERE s.id=? AND s.workspace_id=? AND s.deleted_at IS NULL";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(scheduleId));
      p.setBytes(2, Database.uuidBytes(workspaceId));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        List<String> parts = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
          String value = rs.getString(index);
          if (value != null && !value.isBlank()) parts.add(value.trim());
        }
        return new ScheduleTopic(String.join(" ", parts));
      }
    }
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
      system.addProperty("content", "你是学习资料整理助手。只输出 JSON：{\"keyPoints\":[\"关键点\"],\"studyNote\":\"一句总建议\",\"sections\":[{\"title\":\"核心理解\",\"content\":\"详细说明\"}]}。关键点最多 4 条，sections 输出 3 至 5 个有标题的详细段落，必须基于提供的资料；资料不足时明确说明，不要编造链接。");
      JsonObject user = new JsonObject(); user.addProperty("role", "user");
      user.addProperty("content", "学习主题：" + topic.query() + "\n\n资料：\n" + source);
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
    if (!news.isEmpty()) points.add("优先打开排名靠前的原文，记录定义、步骤和一个可验证的例子。" );
    points.add("学习结束后用自己的话复述要点，并把仍然不清楚的概念写入本次笔记。" );
    JsonArray sections = new JsonArray();
    sections.add(section("核心理解", "先明确“" + topic.query() + "”要解决的问题，再区分概念、方法和实际执行步骤。不要只记结论，要能说明它为什么成立、什么时候适用。"));
    sections.add(section("学习路径", "先用一份入门资料建立整体框架，再选择一个可靠原文核对细节；学习过程中把定义、步骤和一个具体例子分别记录下来。"));
    sections.add(section("实践建议", "把今天的主题拆成一个 20 至 30 分钟的小练习，完成后用自己的话复述要点，并记录一个仍然不清楚的问题。"));
    sections.add(section("资料使用提醒", news.isEmpty() ? "当前没有获取到联网资料，下面的平台入口可用于继续查找；联网恢复后点击“重新获取”更新整理结果。" : "联网资料只作为参考入口，优先交叉核对来源、发布时间和原文上下文，不要只依据搜索摘要下结论。"));
    JsonObject result = new JsonObject(); result.add("keyPoints", points); result.add("sections", sections);
    result.addProperty("studyNote", "这份整理先帮你建立框架，再把内容落到一个可执行的小练习。" );
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
