package com.changlu.planner.agent.subagents.diet.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.Set;

/**
 * 只读网络搜索（设计 §7.1）：检索膳食指南与常见食材营养参考。
 * 搜索词只发主题词（目标/饮食类型/忌口），由编排器保证不携带身高体重等个人信息。
 */
public final class NutritionReferenceTool implements ToolHandler {
  public static final String NAME = "diet.nutrition.reference";
  private final WebSearchTool search;

  public NutritionReferenceTool(WebSearchTool search) { this.search = search; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"query":{"type":"string","minLength":1}},"required":["query"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString("""
        {"type":"object","properties":{"sources":{"type":"array"}},"required":["sources"]}
        """).getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "检索膳食指南、常见食材热量与营养参考",
        input, output, Set.of("diet:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE,
        false, Duration.ofSeconds(20), RetryPolicy.readOnlyNetwork());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    String query = call.arguments().has("query") ? call.arguments().get("query").getAsString().trim() : "";
    if (query.isBlank()) throw new IllegalArgumentException("DIET_RESEARCH_QUERY_REQUIRED");
    JsonArray sources = new JsonArray();
    for (WebSearchTool.Result result : search.search(query, 8, false)) {
      JsonObject row = new JsonObject();
      row.addProperty("title", result.title()); row.addProperty("summary", result.summary());
      row.addProperty("source", result.source()); row.addProperty("url", result.url());
      sources.add(row);
    }
    JsonObject data = new JsonObject(); data.add("sources", sources);
    return AgentResult.completed(sources.isEmpty() ? "暂时没有找到营养参考资料。" : "已找到营养参考资料。",
        data, context.traceId());
  }
}
