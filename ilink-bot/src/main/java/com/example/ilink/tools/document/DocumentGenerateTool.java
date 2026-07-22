package com.example.ilink.tools.document;

import com.example.ilink.conversation.DocumentSessionStore;
import com.example.ilink.feature.document.DocumentAiService;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.model.DocumentRecord;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 文档生成工具。 */
public final class DocumentGenerateTool implements Tool {

    public static final String NAME = "generate_document";

    private final DocumentAiService documentAiService;
    private final DocumentService documentService;
    private final DocumentSessionStore documentSessions;
    private final ToolDefinition definition;

    /** 创建文档生成工具。 */
    public DocumentGenerateTool(DocumentAiService documentAiService,
                                DocumentService documentService,
                                DocumentSessionStore documentSessions) {
        this.documentAiService = documentAiService;
        this.documentService = documentService;
        this.documentSessions = documentSessions;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("需要生成的文件内容和格式要求"));
        properties.add("output_type", ToolDefinition.enumStringProperty("输出文件格式", "docx", "pdf"));
        this.definition = new ToolDefinition(
                NAME,
                "生成文档",
                "根据用户要求生成 DOCX 或 PDF；存在当前文档时也可以基于当前文档生成总结文件。",
                ToolDefinition.objectParameters(properties, "request", "output_type"),
                true);
    }

    /** 返回文档生成工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 生成正文，再转换为指定文件格式。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String request = ToolArguments.requireString(arguments, "request");
        String outputType = ToolArguments.string(arguments, "output_type", "docx");
        DocumentRecord document = documentSessions.get(context.userId());

        String content = document == null
                ? documentAiService.generateDocument(context.userId(), request)
                : documentAiService.chatWithDocument(
                        context.userId(), request, document.fileName(), document.text());
        if (content == null || content.isBlank()) {
            return ToolResult.failure("文档内容生成失败");
        }

        String title = document == null ? "生成文件" : document.fileName() + "总结";
        byte[] bytes = "pdf".equals(outputType)
                ? documentService.createPdf(title, content)
                : documentService.createDocx(title, content);
        String fileName = "generated." + outputType;
        return ToolResult.success("文件已生成",
                new DocumentToolOutput(bytes, outputType, fileName, content, "文件已生成"));
    }
}
