package com.changlu.planner.agent.subagents.document;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.subagents.document.rag.RagService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.Set;

/** 负责文件识别、知识检索、总结和问答，不直接修改计划数据。 */
public final class DocumentSubagent implements Subagent {
  private final DocumentParserTool parser = new DocumentParserTool();
  private final RagService rag;
  private final ModelClient model;
  private final SubagentDefinition definition = new SubagentDefinition(
      "document", "1.0.0", "识别上传文件并基于用户知识库完成总结、提取和问答",
      List.of("分析文件", "总结文件", "文件", "附件", "知识库"),
      List.of("根据文件创建或调整计划", "根据文件创建任务"),
      new JsonObject(), new JsonObject(), Set.of(), false, false, Duration.ofSeconds(120), 2);

  public DocumentSubagent(Database database, ModelClient model) {
    this.rag = new RagService(database);
    this.model = model;
  }

  public DocumentResult upload(byte[] bytes, String fileName, String mediaType, Database.Context context)
      throws Exception {
    return rag.index(context, parser.parse(bytes, fileName, mediaType));
  }

  public void delete(String documentId, Database.Context context) throws Exception {
    rag.delete(context, UUID.fromString(documentId));
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    RagService.RetrievedContext knowledge = rag.retrieve(
        context.identity(), request.message(), request.documentIds().stream().limit(8).map(UUID::fromString).toList());
    if (knowledge.isEmpty()) {
      JsonObject result = new JsonObject();
      result.addProperty("reply", "还没有找到可分析的文件内容，请先上传文件后再试。");
      result.add("sources", new JsonArray());
      return AgentResult.completed(result.get("reply").getAsString(), result, context.traceId());
    }

    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的文件分析 Subagent。只能依据提供的用户文件内容回答。
        可以总结、提取要点、比较内容和回答问题；无法从资料确认的信息要明确说明。
        在回答中用“[文件名]”标注关键结论来源。
        只输出 JSON：{"reply":"中文回答","sources":["实际使用的文件名"]}。
        用户文件内容：
        """ + knowledge.prompt()));
    String shared = context.sharedContext();
    if (shared != null && !shared.isBlank()) {
      messages.add(ModelClient.message("system", "已知的用户长期记忆与最近对话（供理解上下文）：\n" + shared));
    }
    messages.add(ModelClient.message("user", request.message()));

    JsonObject result;
    try {
      result = model.completeJson("document-subagent", messages, 0.1, 2200, 90, 2);
    } catch (Exception error) {
      result = new JsonObject();
      String preview = knowledge.prompt().replaceAll("\\s+", " ");
      if (preview.length() > 420) preview = preview.substring(0, 420) + "...";
      result.addProperty("reply", "文件已经识别并建立索引。当前无法调用 AI 深入分析，已提取内容预览：\n" + preview);
    }
    JsonArray sources = new JsonArray();
    knowledge.sources().forEach(sources::add);
    result.add("sources", sources);
    return AgentResult.completed(result.get("reply").getAsString(), result, context.traceId());
  }

  public String planningContext(JsonObject input, Database.Context context, String request) throws Exception {
    if (!hasDocumentReference(input, request)) return "";
    return rag.retrieve(context, request, documentIds(input)).prompt();
  }

  public boolean hasAttachments(JsonObject input) { return !documentIds(input).isEmpty(); }

  private boolean hasDocumentReference(JsonObject input, String request) {
    if (hasAttachments(input)) return true;
    String value = request == null ? "" : request.replaceAll("\\s", "");
    return value.contains("文件") || value.contains("文档") || value.contains("资料")
        || value.contains("附件") || value.contains("知识库");
  }

  private List<UUID> documentIds(JsonObject input) {
    if (input == null || !input.has("documentIds") || !input.get("documentIds").isJsonArray()) {
      return List.of();
    }
    List<UUID> ids = new ArrayList<>();
    input.getAsJsonArray("documentIds").forEach(value -> {
      if (ids.size() < 8) ids.add(UUID.fromString(value.getAsString()));
    });
    return List.copyOf(ids);
  }

}
