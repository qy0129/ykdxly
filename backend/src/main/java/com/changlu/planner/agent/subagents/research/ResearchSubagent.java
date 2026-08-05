package com.changlu.planner.agent.subagents.research;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** 搜索公开网页并返回带来源链接的研究材料，不接触计划数据库。 */
public final class ResearchSubagent implements Subagent {
  private final WebSearchTool search;
  private final SubagentDefinition definition = new SubagentDefinition(
      "research", "1.0.0", "Search public web pages and return sourced research material",
      List.of("搜索", "查资料", "查新闻", "资料研究"), List.of(),
      new JsonObject(), new JsonObject(), Set.of(), true, false, Duration.ofSeconds(90), 2);

  public ResearchSubagent() { this(new WebSearchTool()); }
  public ResearchSubagent(WebSearchTool search) { this.search = search; }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) {
    ResearchResult result = new ResearchResult(search.search(request.message(), 5, false));
    return AgentResult.completed(result.reply(), result.toJson(), context.traceId());
  }
}
