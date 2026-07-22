package com.example.ilink.tools.document;

import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.feature.document.DocumentAiService;
import com.example.ilink.feature.document.DocumentEditPlan;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

import java.nio.file.Path;

/** Function Calling 文档编辑工具。 */
public final class DocumentEditTool implements Tool {

    public static final String NAME = "edit_document";

    private final DocumentAiService documentAiService;
    private final DocumentService documentService;
    private final DocumentSessionStore documentSessions;
    private final ToolDefinition definition;

    /** 创建文档编辑工具。 */
    public DocumentEditTool(DocumentAiService documentAiService,
                            DocumentService documentService,
                            DocumentSessionStore documentSessions) {
        this.documentAiService = documentAiService;
        this.documentService = documentService;
        this.documentSessions = documentSessions;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("对当前文档的完整修改要求"));
        properties.add("output_type", ToolDefinition.enumStringProperty("修改后的输出格式", "docx", "pdf"));
        this.definition = new ToolDefinition(
                NAME,
                "编辑文档",
                "按照用户要求修改最近发送的文档并生成新文件。没有当前文档时不要调用。",
                ToolDefinition.objectParameters(properties, "request", "output_type"),
                true);
    }

    /** 返回文档编辑工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** DOCX 优先在原文件上替换，其他格式生成修改后的完整文件。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        DocumentRecord document = documentSessions.get(context.userId());
        if (document == null) {
            return ToolResult.failure("请先发送需要修改的文档");
        }

        String request = ToolArguments.requireString(arguments, "request");
        String outputType = ToolArguments.string(arguments, "output_type", "docx");
        if ("docx".equals(document.extension()) && "docx".equals(outputType)) {
            DocumentEditPlan plan = documentAiService.planDocxEdits(
                    document.fileName(), document.text(), request);
            if (plan == null || plan.edits().isEmpty()) {
                return ToolResult.failure("没有生成可执行的 DOCX 修改指令");
            }
            DocumentService.DocxEditResult result = documentService.editDocx(
                    Path.of(document.path()), plan.edits());
            String caption = result.unmatchedTargets().isEmpty()
                    ? "已在原 DOCX 上完成修改"
                    : "已完成 " + result.appliedEdits() + " 项修改，有 "
                            + result.unmatchedTargets().size() + " 项未定位";
            return ToolResult.success(caption,
                    new DocumentToolOutput(result.document(), "docx", "modified.docx", null, caption));
        }

        String content = documentAiService.chatWithDocument(
                context.userId(),
                "请按照用户要求修改文件，只输出修改后的完整内容。用户要求：" + request,
                document.fileName(), document.text());
        if (content == null || content.isBlank()) {
            return ToolResult.failure("文档修改失败");
        }
        byte[] bytes = "pdf".equals(outputType)
                ? documentService.createPdf(document.fileName() + "修改版", content)
                : documentService.createDocx(document.fileName() + "修改版", content);
        return ToolResult.success("文档修改完成",
                new DocumentToolOutput(bytes, outputType, "modified." + outputType,
                        content, "文档修改完成"));
    }
}
