package com.changlu.planner.agent.subagents.review;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.Subagent;
import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 基于真实执行记录生成只读日报，不产生任何计划变更。 */
public final class ReviewSubagent implements Subagent {
  private static final Logger LOG = LoggerFactory.getLogger(ReviewSubagent.class);
  private final Database database;
  private final AiCommandService commands;
  private final ModelClient model;
  private final Gson gson = new Gson();

  public ReviewSubagent(Database database, AiCommandService commands, ModelClient model) {
    this.database = database;
    this.commands = commands;
    this.model = model;
  }

  @Override public String name() { return "review"; }
  @Override public String description() { return "根据今日真实完成、延期、阻塞和专注记录生成只读复盘"; }

  @Override public JsonObject execute(String request, AgentContext context) throws Exception {
    ReviewResult report = todayResult(context.identity(), request.contains("重新") || request.contains("刷新"));
    JsonObject result = new JsonObject();
    result.addProperty("reply", report.summary());
    result.add("report", report.toJson());
    return result;
  }

  public JsonObject today(Database.Context context, boolean force) throws Exception {
    return todayResult(context, force).toJson();
  }

  private ReviewResult todayResult(Database.Context context, boolean force) throws Exception {
    JsonObject facts = commands.reviewFacts(context);
    if (!force) {
      ReviewResult cached = cached(context, facts);
      if (cached != null) return cached;
    }
    if (!model.configured()) {
      throw new IllegalStateException("AI 复盘不可用，请配置 api.key 或 PLANNER_AI_API_KEY");
    }
    String generatedAt = LocalDateTime.now().toString();
    ReviewResult result;
    try {
      JsonObject generated = model.completeJson(
          "review-subagent", ReviewPrompt.messages(facts), 0.2, 1800, 25, 1);
      result = ReviewResult.fromGenerated(facts, generated, generatedAt);
    } catch (ModelClient.InvalidJsonException formatError) {
      // 结构化输出失败时保留真实 AI 原文，避免退回固定模板或直接丢失复盘。
      LOG.warn("[复盘返回非 JSON] 用户={} 工作区={}，已按 AI 原文保存", context.userId(), context.workspaceId());
      result = ReviewResult.fromPlainText(facts, formatError.content(), generatedAt);
    } catch (Exception error) {
      LOG.warn("[复盘生成失败] 用户={} 工作区={} 原因={}", context.userId(), context.workspaceId(),
          error.getMessage(), error);
      String reason = error.getMessage() == null || error.getMessage().isBlank()
          ? "模型没有返回有效结果" : error.getMessage();
      throw new IllegalStateException("AI 复盘生成失败：" + reason, error);
    }
    save(context, result);
    return result;
  }

  private ReviewResult cached(Database.Context context, JsonObject facts) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT facts,ai_summary,ai_suggestions,updated_at FROM review_entries "
            + "WHERE workspace_id=? AND user_id=? AND review_date=? "
            + "AND ai_summary IS NOT NULL AND ai_suggestions IS NOT NULL")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      String reviewDate = facts.has("date") && !facts.get("date").isJsonNull()
          ? facts.get("date").getAsString() : java.time.LocalDate.now().toString();
      p.setDate(3, java.sql.Date.valueOf(reviewDate));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        String cachedFactsText = rs.getString("facts");
        if (cachedFactsText == null || cachedFactsText.isBlank()) return null;
        JsonObject cachedFacts;
        JsonObject suggestions;
        try {
          cachedFacts = JsonParser.parseString(cachedFactsText).getAsJsonObject();
          suggestions = JsonParser.parseString(rs.getString("ai_suggestions")).getAsJsonObject();
        } catch (RuntimeException malformedJson) {
          return null;
        }
        // 兼容事实结构升级：旧缓存可以缺少新增字段，但已有字段必须全部一致。
        // 这样不会因为增加 recentExecution 等派生字段而触发不必要的模型请求。
        if (!factsCompatible(cachedFacts, facts)
            || !suggestions.has("aiGenerated")
            || !suggestions.get("aiGenerated").getAsBoolean()) return null;
        String summary = rs.getString("ai_summary");
        String generatedAt = rs.getTimestamp("updated_at").toLocalDateTime().toString();
        ReviewResult cached = ReviewResult.fromCache(facts, summary, suggestions, generatedAt);
        // 兼容之前把完整 JSON 当成 summary 保存的旧记录，读取时自动迁移一次。
        if (summary != null && summary.indexOf('{') >= 0 && summary.lastIndexOf('}') > summary.indexOf('{')) {
          ReviewResult normalized = ReviewResult.fromPlainText(facts, summary, generatedAt);
          if (!normalized.summary().equals(summary)) {
            save(context, normalized);
            return normalized;
          }
        }
        return cached;
      }
    }
  }

  private void save(Database.Context context, ReviewResult result) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "INSERT INTO review_entries "
            + "(id,workspace_id,user_id,review_date,facts,ai_summary,ai_suggestions) "
            + "VALUES (?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
            + "facts=VALUES(facts),ai_summary=VALUES(ai_summary),ai_suggestions=VALUES(ai_suggestions)")) {
      p.setBytes(1, Database.uuidBytes(java.util.UUID.randomUUID()));
      p.setBytes(2, Database.uuidBytes(context.workspaceId()));
      p.setBytes(3, Database.uuidBytes(context.userId()));
      p.setDate(4, java.sql.Date.valueOf(result.date()));
      p.setString(5, gson.toJson(result.facts()));
      p.setString(6, result.summary());
      p.setString(7, gson.toJson(result.suggestions()));
      p.executeUpdate();
    }
  }

  private boolean factsCompatible(JsonObject cachedFacts, JsonObject currentFacts) {
    for (String key : cachedFacts.keySet()) {
      // recentExecution 是近 7 天的派生列表，旧缓存没有该字段或排序略有差异时不应使今日复盘失效。
      if ("recentExecution".equals(key)) continue;
      if (!currentFacts.has(key) || !cachedFacts.get(key).equals(currentFacts.get(key))) return false;
    }
    return true;
  }
}
