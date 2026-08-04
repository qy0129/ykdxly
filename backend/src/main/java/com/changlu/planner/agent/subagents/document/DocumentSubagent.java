package com.changlu.planner.agent.subagents.document;

import com.changlu.planner.agent.core.AgentContext;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.Subagent;
import com.changlu.planner.agent.subagents.document.rag.RagService;
import com.changlu.planner.shared.database.Database;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 负责文件识别、知识检索、总结和问答，不直接修改计划数据。 */
public final class DocumentSubagent implements Subagent {
  private final DocumentParserTool parser = new DocumentParserTool();
  private final RagService rag;
  private final ModelClient model;

  public DocumentSubagent(Database database, ModelClient model) {
    this.rag = new RagService(database);
    this.model = model;
  }

  @Override public String name() { return "document"; }
  @Override public String description() { return "识别上传文件，并基于用户知识库完成总结、提取和问答"; }

  public DocumentResult upload(byte[] bytes, String fileName, String mediaType, Database.Context context)
      throws Exception {
    return rag.index(context, parser.parse(bytes, fileName, mediaType));
  }

  public void delete(String documentId, Database.Context context) throws Exception {
    rag.delete(context, UUID.fromString(documentId));
  }

  @Override public JsonObject execute(String request, AgentContext context) throws Exception {
    RagService.RetrievedContext knowledge = rag.retrieve(
        context.identity(), request, documentIds(context.input()));
    if (knowledge.isEmpty()) {
      JsonObject result = new JsonObject();
      result.addProperty("reply", "还没有找到可分析的文件内容，请先上传文件后再试。");
      result.add("sources", new JsonArray());
      return result;
    }

    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的文件分析 Subagent。只能依据提供的用户文件内容回答。
        可以总结、提取要点、比较内容和回答问题；无法从资料确认的信息要明确说明。
        在回答中用“[文件名]”标注关键结论来源。
        只输出 JSON：{"reply":"中文回答","sources":["实际使用的文件名"]}。
        用户文件内容：
        """ + knowledge.prompt()));
    messages.add(ModelClient.message("user", request));

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
    return result;
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
