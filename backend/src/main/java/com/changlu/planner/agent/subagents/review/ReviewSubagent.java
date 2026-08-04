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
    String generatedAt = LocalDateTime.now().toString();
    ReviewResult result;
    try {
      JsonObject generated = model.completeJson(
          "review-subagent", ReviewPrompt.messages(facts), 0.2, 1800, 25, 1);
      result = ReviewResult.fromGenerated(facts, generated, generatedAt);
    } catch (Exception error) {
      LOG.warn("[复盘生成失败] 用户={} 工作区={} 原因={}", context.userId(), context.workspaceId(),
          error.getMessage());
      result = ReviewResult.fallback(facts, generatedAt);
    }
    save(context, result);
    return result;
  }

  private ReviewResult cached(Database.Context context, JsonObject facts) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "SELECT ai_summary,ai_suggestions,updated_at FROM review_entries "
            + "WHERE workspace_id=? AND user_id=? AND review_date=CURDATE() AND ai_summary IS NOT NULL")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId()));
      p.setBytes(2, Database.uuidBytes(context.userId()));
      try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return null;
        JsonObject suggestions = rs.getString("ai_suggestions") == null
            ? new JsonObject() : JsonParser.parseString(rs.getString("ai_suggestions")).getAsJsonObject();
        return ReviewResult.fromCache(facts, rs.getString("ai_summary"), suggestions,
            rs.getTimestamp("updated_at").toLocalDateTime().toString());
      }
    }
  }

  private void save(Database.Context context, ReviewResult result) throws Exception {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
        "UPDATE review_entries SET ai_summary=?,ai_suggestions=? "
            + "WHERE workspace_id=? AND user_id=? AND review_date=CURDATE()")) {
      p.setString(1, result.summary());
      p.setString(2, gson.toJson(result.suggestions()));
      p.setBytes(3, Database.uuidBytes(context.workspaceId()));
      p.setBytes(4, Database.uuidBytes(context.userId()));
      p.executeUpdate();
    }
  }
}
