package com.example.ilink.capabilities.documents;

import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/**
 * 计划文件生成工具。
 *
 * <p>计划内容已经由规划工具生成完成，因此这里只负责本地转换为 DOCX 或 PDF，
 * 不再重复调用文档 AI，适合计划工作流快速生成文件。</p>
 */
public final class PlanDocumentTool implements Tool {

    public static final String NAME = "generate_plan_document";

    private final DocumentService documentService;
    private final ToolDefinition definition;

    /** 创建计划文件工具。 */
    public PlanDocumentTool(DocumentService documentService) {
        this.documentService = documentService;
        JsonObject properties = new JsonObject();
        properties.add("content", ToolDefinition.stringProperty("已经生成好的完整任务计划文本"));
        properties.add("output_type", ToolDefinition.enumStringProperty(
                "输出文件格式", "docx", "pdf", "xlsx", "pptx"));
        this.definition = new ToolDefinition(
                NAME,
                "生成计划文件",
                "将已经生成好的计划文本直接转换为 DOCX、PDF、XLSX 或 PPTX，不调用额外的 AI 文档生成请求。",
                ToolDefinition.objectParameters(properties, "content", "output_type"),
                true);
    }

    /** 返回计划文件工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 将计划文本转换为本地文件字节。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        String content = ToolArguments.requireString(arguments, "content");
        String outputType = ToolArguments.string(arguments, "output_type", "docx");
        String title = "任务计划";
        byte[] bytes = switch (outputType) {
            case "xlsx" -> documentService.createXlsx(content);
            case "pptx" -> documentService.createPptx(title, content);
            case "pdf" -> documentService.createPdf(title, content);
            default -> documentService.createDocx(title, content);
        };
        String fileName = "task-plan." + outputType;
        return ToolResult.success("计划文件已生成", new DocumentToolOutput(
                bytes, outputType, fileName, content, "任务计划文件"));
    }
}
