package com.changlu.planner.agent.subagents.research;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.Subagent;
import com.google.gson.JsonObject;

/** 搜索公开网页并返回带来源链接的研究材料，不接触计划数据库。 */
public final class ResearchSubagent implements Subagent {
  private final WebSearchTool search;

  public ResearchSubagent() { this(new WebSearchTool()); }
  public ResearchSubagent(WebSearchTool search) { this.search = search; }

  @Override public String name() { return "research"; }
  @Override public String description() { return "搜索公开网页并返回带来源链接的研究材料"; }

  @Override public JsonObject execute(String request, AgentContext context) {
    return new ResearchResult(search.search(request, 5, false)).toJson();
  }
}
