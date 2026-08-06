package com.changlu.planner.agent.core;

import com.changlu.planner.agent.subagents.briefing.BriefingResult;
import com.changlu.planner.agent.subagents.briefing.BriefingSubagent;
import com.changlu.planner.agent.subagents.document.DocumentResult;
import com.changlu.planner.agent.subagents.document.DocumentSubagent;
import com.changlu.planner.agent.subagents.diet.DietModule;
import com.changlu.planner.agent.subagents.image.ImageModule;
import com.changlu.planner.agent.subagents.memory.MemorySubagent;
import com.changlu.planner.agent.subagents.research.ResearchSubagent;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.agent.subagents.learning.LearningSubagent;
import com.changlu.planner.agent.subagents.learning.tools.LearningResearchTool;
import com.changlu.planner.agent.subagents.review.ReviewSubagent;
import com.changlu.planner.agent.subagents.scheduling.ConflictTool;
import com.changlu.planner.agent.subagents.scheduling.SchedulingSubagent;
import com.changlu.planner.agent.subagents.travel.TravelModule;
import com.changlu.planner.agent.tools.PlanningAssistantTool;
import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

/** 网页和微信共用的 Agent 应用入口。 */
public final class AgentFacade implements AutoCloseable {
  private final AgentRuntime runtime;
  private final ReviewSubagent review;
  private final BriefingSubagent briefing;
  private final DocumentSubagent documents;
  private final MemorySubagent memory;
  private final AiCommandService commands;

  public AgentFacade(Database database) {
    ModelClient model = new ModelClient();
    memory = new MemorySubagent(database, model);
    commands = new AiCommandService(database, model, memory);
    com.changlu.planner.agent.core.registry.SubagentRegistry subagents =
        new com.changlu.planner.agent.core.registry.SubagentRegistry();
    com.changlu.planner.agent.core.tool.ToolRegistry standardTools =
        new com.changlu.planner.agent.core.tool.ToolRegistry();
    standardTools.register(new PlanningAssistantTool(commands));
    WebSearchTool webSearch = new WebSearchTool();
    review = new ReviewSubagent(database, commands, model);
    briefing = new BriefingSubagent(database, webSearch);
    documents = new DocumentSubagent(database, model);

    // 所有 Subagent 已实现新契约，自带 definition 与场景元数据，直接注册即可。
    subagents.register(review);
    subagents.register(briefing);
    subagents.register(new ResearchSubagent(webSearch));
    subagents.register(new SchedulingSubagent(new ConflictTool(database)));
    subagents.register(documents);
    subagents.register(memory);
    LearningService learningService = new LearningService(database);
    standardTools.register(new LearningResearchTool(webSearch));
    subagents.register(new LearningSubagent(learningService, model, commands, standardTools));

    // New-contract module: registers the travel subagent and its tools into the standard tool registry.
    new TravelModule(model, commands, webSearch).register(subagents, standardTools);
    new DietModule(model, commands, webSearch).register(subagents, standardTools);
    // 文生图与其他领域能力一样挂入统一注册表，网页和微信都会经过同一个 Agent 循环。
    new ImageModule(database).register(subagents, standardTools);

    runtime = new AgentRuntime(database, commands, new AgentRouter(model), standardTools,
        subagents, documents, memory);

  }

  public JsonObject start(JsonObject input, Database.Context context, String channel) throws Exception {
    return runtime.start(input, context, channel);
  }

  public JsonObject startAsync(JsonObject input, Database.Context context, String channel) throws Exception {
    return runtime.startAsync(input, context, channel);
  }

  public JsonObject get(String runId, Database.Context context) throws Exception { return runtime.get(runId, context); }
  public JsonObject resume(String runId, JsonObject input, Database.Context context) throws Exception {
    return runtime.resume(runId, input, context);
  }
  public JsonObject resumeAsync(String runId, JsonObject input, Database.Context context) throws Exception {
    return runtime.resumeAsync(runId, input, context);
  }
  public JsonObject confirm(String draftId, Database.Context context) throws Exception { return runtime.confirm(draftId, context); }
  public JsonObject cancel(String draftId, Database.Context context) throws Exception { return runtime.cancel(draftId, context); }
  public JsonObject modifyDraft(String draftId, JsonObject input, Database.Context context) throws Exception {
    return runtime.modifyDraft(draftId, input, context);
  }
  public JsonObject session(Database.Context context, String channel) throws Exception { return runtime.session(context, channel); }
  public JsonArray conversations(Database.Context context, String channel) throws Exception {
    return commands.conversations(context, channel);
  }
  public JsonObject createConversation(Database.Context context, String channel) throws Exception {
    return runtime.createConversation(context, channel);
  }
  public JsonObject conversation(String id, Database.Context context, String channel) throws Exception {
    return runtime.conversation(id, context, channel);
  }
  public JsonObject renameConversation(String id, JsonObject input, Database.Context context) throws Exception {
    return commands.renameConversation(id, input, context);
  }
  public void deleteConversation(String id, Database.Context context) throws Exception {
    commands.deleteConversation(id, context);
  }
  public JsonArray memories(Database.Context context) throws Exception { return memory.list(context); }
  /** 供提醒等外部模块读取该用户的长期记忆上下文。 */
  public String memoryContext(Database.Context context) throws Exception { return memory.context(context); }
  public JsonObject updateMemory(String id, JsonObject input, Database.Context context) throws Exception {
    return memory.update(id, input, context);
  }
  public void deleteMemory(String id, Database.Context context) throws Exception { memory.delete(id, context); }
  public JsonObject reviewToday(Database.Context context, boolean force) throws Exception { return review.today(context, force); }
  public BriefingResult briefing(String externalUserId) throws Exception { return briefing.build(externalUserId); }
  public DocumentResult uploadDocument(byte[] bytes, String fileName, String mediaType,
                                       Database.Context context) throws Exception {
    return documents.upload(bytes, fileName, mediaType, context);
  }
  public void deleteDocument(String documentId, Database.Context context) throws Exception {
    documents.delete(documentId, context);
  }
  public JsonObject draft(String draftId, Database.Context context) throws Exception { return commands.draft(draftId, context); }
  public JsonObject undo(String changeSetId, Database.Context context) throws Exception { return commands.undo(changeSetId, context); }
  public JsonObject reviewFacts(Database.Context context) throws Exception { return commands.reviewFacts(context); }

  @Override
  public void close() { runtime.close(); }
}
