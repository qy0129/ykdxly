package com.example.ilink.capabilities.knowledge.tool;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.example.ilink.capabilities.knowledge.KnowledgeQueryService;
import com.google.gson.JsonObject;

import static com.example.ilink.application.tooling.ToolDefinition.*;

public final class KnowledgeQueryTool implements Tool {

    public static final String NAME = "knowledge_query";

    private final KnowledgeQueryService queryService;

    public KnowledgeQueryTool(KnowledgeQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public ToolDefinition definition() {
        JsonObject properties = new JsonObject();
        properties.add("question", stringProperty("用户关于知识库内容的问题"));

        return new ToolDefinition(
                NAME, "知识库查询",
                "在用户已上传的知识库中搜索与问题相关的内容并返回引用。"
                        + "当用户询问文档内容、技术资料或已上传文件中的信息时使用此工具。",
                objectParameters(properties, "question"),
                true);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("缺少用户标识");
        }
        String question = arguments.has("question") && !arguments.get("question").isJsonNull()
                ? arguments.get("question").getAsString().trim() : "";
        if (question.isBlank()) {
            return ToolResult.failure("请提供要查询的问题");
        }
        if (!queryService.hasKnowledge(userId)) {
            return ToolResult.failure("你还没有上传任何知识库文件。请先上传 PDF、Word 或 Markdown 文件。");
        }
        KnowledgeQueryService.KnowledgeResult result = queryService.query(userId, question);
        if (!result.found()) {
            return ToolResult.success("知识库中没有找到与问题相关的内容。");
        }
        StringBuilder output = new StringBuilder();
        output.append("找到 ").append(result.references().size()).append(" 条相关知识：\n\n");
        for (int i = 0; i < result.references().size(); i++) {
            var ref = result.references().get(i);
            output.append("【参考").append(i + 1).append("】")
                    .append(ref.toCitation()).append("\n")
                    .append(ref.content()).append("\n\n");
        }
        return ToolResult.success(output.toString().trim(), result.references());
    }
}
