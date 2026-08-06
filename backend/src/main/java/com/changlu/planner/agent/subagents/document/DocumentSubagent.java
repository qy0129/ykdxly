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
    List<UUID> documentIds = request.documentIds().stream().limit(8).map(UUID::fromString).toList();
    // 只上传了文件、没说要做什么（空请求或“分析一下”这类泛泛说法）：先追问需求，不直接分析，避免答非所问。
    if (!documentIds.isEmpty() && isVagueRequest(request.message())) {
      return askWhatToDo(documentIds, context);
    }
    RagService.RetrievedContext knowledge = rag.retrieve(context.identity(), request.message(), documentIds);
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

  /** 出现这些词说明用户已经提出了具体任务或问题，不需要追问。 */
  private static final String[] TASK_KEYWORDS = {
      "总结", "归纳", "摘要", "概括", "要点", "重点", "关键", "提取", "比较", "对比",
      "区别", "差异", "回答", "解释", "介绍", "翻译", "统计", "列出", "列举", "评价",
      "计划", "任务", "规划", "生成", "制定", "创建", "安排", "写入",
      "什么", "如何", "怎么", "多少", "是否", "吗", "?", "？",
  };

  /** 请求是否没说明要对文件做什么：空白，或只是“分析/看看/这个文件”这类泛泛说法。 */
  private boolean isVagueRequest(String message) {
    String value = message == null ? "" : message.trim();
    if (value.isBlank()) return true;
    for (String keyword : TASK_KEYWORDS) {
      if (value.contains(keyword)) return false;
    }
    return true;
  }

  /** 追问用户对上传文件的需求，run 暂停在 WAITING_USER；用户回答后带着同样的附件继续分析。 */
  private AgentResult askWhatToDo(List<UUID> documentIds, AgentContext context) {
    String guidance = "已收到你上传的文件。你希望对它做什么？例如：总结内容、提取要点、回答我的问题、根据文件制定计划或任务。请直接告诉我你的需求。";
    JsonObject data = new JsonObject();
    data.addProperty("reply", guidance);
    JsonArray questions = new JsonArray();
    questions.add(guidance);
    data.add("questions", questions);
    JsonArray savedDocumentIds = new JsonArray();
    documentIds.forEach(id -> savedDocumentIds.add(id.toString()));
    data.add("documentIds", savedDocumentIds);
    return AgentResult.waitingUser(guidance, data, context.traceId());
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
