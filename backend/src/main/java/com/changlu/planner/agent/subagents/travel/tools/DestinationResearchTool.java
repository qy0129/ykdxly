package com.changlu.planner.agent.subagents.travel.tools;

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

public final class DestinationResearchTool implements ToolHandler {
  public static final String NAME = "travel.destination.research";
  private final WebSearchTool search;

  public DestinationResearchTool(WebSearchTool search) { this.search = search; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"query":{"type":"string","minLength":1}},"required":["query"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString("""
        {"type":"object","properties":{"sources":{"type":"array"}},"required":["sources"]}
        """).getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "搜索目的地景点、交通、开放时间和旅行注意事项",
        input, output, Set.of("travel:read"), ToolRiskLevel.READ_ONLY, ToolSideEffect.NONE,
        false, Duration.ofSeconds(20), RetryPolicy.readOnlyNetwork());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) {
    String query = call.arguments().has("query") ? call.arguments().get("query").getAsString().trim() : "";
    if (query.isBlank()) throw new IllegalArgumentException("TRAVEL_RESEARCH_QUERY_REQUIRED");
    JsonArray sources = new JsonArray();
    for (WebSearchTool.Result result : search.search(query, 8, false)) {
      JsonObject row = new JsonObject();
      row.addProperty("title", result.title()); row.addProperty("summary", result.summary());
      row.addProperty("source", result.source()); row.addProperty("url", result.url());
      sources.add(row);
    }
    JsonObject data = new JsonObject(); data.add("sources", sources);
    return AgentResult.completed(sources.isEmpty() ? "暂时没有找到公开资料。" : "已找到公开旅行资料。",
        data, context.traceId());
  }
}
