package com.changlu.planner.agent.core;

import com.changlu.planner.agent.subagents.briefing.BriefingResult;
import com.changlu.planner.agent.subagents.briefing.BriefingSubagent;
import com.changlu.planner.agent.subagents.research.ResearchSubagent;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.agent.subagents.review.ReviewSubagent;
import com.changlu.planner.agent.subagents.scheduling.ConflictTool;
import com.changlu.planner.agent.subagents.scheduling.SchedulingSubagent;
import com.changlu.planner.agent.tools.PlanningTools;
import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;

/** 网页和微信共用的 Agent 应用入口。 */
public final class AgentFacade {
  private final AgentRuntime runtime;
  private final ReviewSubagent review;
  private final BriefingSubagent briefing;
  private final AiCommandService commands;

  public AgentFacade(Database database) {
    ModelClient model = new ModelClient();
    commands = new AiCommandService(database, model);
    ToolRegistry tools = PlanningTools.registry();
    SubagentRegistry subagents = new SubagentRegistry();
    WebSearchTool webSearch = new WebSearchTool();
    review = new ReviewSubagent(database, commands, model);
    briefing = new BriefingSubagent(database, webSearch);
    subagents.register(review);
    subagents.register(briefing);
    subagents.register(new ResearchSubagent(webSearch));
    subagents.register(new SchedulingSubagent(new ConflictTool(database)));
    runtime = new AgentRuntime(database, commands, new AgentRouter(model), tools, subagents);
  }

  public JsonObject start(JsonObject input, Database.Context context, String channel) throws Exception {
    return runtime.start(input, context, channel);
  }

  public JsonObject get(String runId, Database.Context context) throws Exception { return runtime.get(runId, context); }
  public JsonObject resume(String runId, JsonObject input, Database.Context context) throws Exception {
    return runtime.resume(runId, input, context);
  }
  public JsonObject confirm(String draftId, Database.Context context) throws Exception { return runtime.confirm(draftId, context); }
  public JsonObject cancel(String draftId, Database.Context context) throws Exception { return runtime.cancel(draftId, context); }
  public JsonObject session(Database.Context context, String channel) throws Exception { return runtime.session(context, channel); }
  public JsonObject reviewToday(Database.Context context, boolean force) throws Exception { return review.today(context, force); }
  public BriefingResult briefing(String externalUserId) throws Exception { return briefing.build(externalUserId); }
  public JsonObject draft(String draftId, Database.Context context) throws Exception { return commands.draft(draftId, context); }
  public JsonObject undo(String changeSetId, Database.Context context) throws Exception { return commands.undo(changeSetId, context); }
  public JsonObject reviewFacts(Database.Context context) throws Exception { return commands.reviewFacts(context); }
}
