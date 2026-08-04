package com.changlu.planner.agent.core;

import com.changlu.planner.agent.subagents.briefing.BriefingResult;
import com.changlu.planner.agent.subagents.briefing.BriefingSubagent;
import com.changlu.planner.agent.subagents.document.DocumentResult;
import com.changlu.planner.agent.subagents.document.DocumentSubagent;
import com.changlu.planner.agent.subagents.memory.MemorySubagent;
import com.changlu.planner.agent.subagents.research.ResearchSubagent;
import com.changlu.planner.agent.subagents.research.WebSearchTool;
import com.changlu.planner.agent.subagents.review.ReviewSubagent;
import com.changlu.planner.agent.subagents.scheduling.ConflictTool;
import com.changlu.planner.agent.subagents.scheduling.SchedulingSubagent;
import com.changlu.planner.agent.subagents.travel.TravelModule;
import com.changlu.planner.agent.tools.PlanningTools;
import com.changlu.planner.features.command.AiCommandService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.List;

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
    ToolRegistry tools = PlanningTools.registry();
    com.changlu.planner.agent.core.registry.SubagentRegistry subagents =
        new com.changlu.planner.agent.core.registry.SubagentRegistry();
    com.changlu.planner.agent.core.tool.ToolRegistry standardTools =
        new com.changlu.planner.agent.core.tool.ToolRegistry();
    WebSearchTool webSearch = new WebSearchTool();
    review = new ReviewSubagent(database, commands, model);
    briefing = new BriefingSubagent(database, webSearch);
    documents = new DocumentSubagent(database, model);
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(review,
        List.of("复盘", "总结今天", "每日复盘"), List.of("修改计划")));
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(briefing,
        List.of("简报", "今日安排", "今日概览"), List.of("修改日程")));
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(
        new ResearchSubagent(webSearch), List.of("搜索", "查资料", "查新闻", "公开网页"), List.of("内部数据查询")));
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(
        new SchedulingSubagent(new ConflictTool(database)), List.of("冲突", "检查日程", "排期问题", "可用时段"),
        List.of("修改日程")));
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(documents,
        List.of("文件", "文档", "附件", "知识库"), List.of("根据文件写入计划")));
    subagents.register(new com.changlu.planner.agent.core.registry.LegacySubagentAdapter(memory,
        List.of("你记得我", "长期记忆", "记住我的", "忘记我的"), List.of("当前会话临时内容")));
    new TravelModule(model, commands, webSearch).register(subagents, standardTools);
    runtime = new AgentRuntime(database, commands, new AgentRouter(model), tools, subagents, standardTools,
        documents, memory);
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
