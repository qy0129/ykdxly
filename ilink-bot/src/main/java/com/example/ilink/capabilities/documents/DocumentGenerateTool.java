package com.example.ilink.capabilities.documents;

import com.example.ilink.capabilities.documents.DocumentAiService;
import com.example.ilink.capabilities.documents.DocumentService;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 文档生成工具。 */
public final class DocumentGenerateTool implements Tool {

    public static final String NAME = "generate_document";

    private final DocumentAiService documentAiService;
    private final DocumentService documentService;
    private final ToolDefinition definition;

    /** 创建文档生成工具。 */
    public DocumentGenerateTool(DocumentAiService documentAiService,
                                DocumentService documentService) {
        this.documentAiService = documentAiService;
        this.documentService = documentService;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("需要生成的文件内容和格式要求"));
        properties.add("output_type", ToolDefinition.enumStringProperty("输出文件格式", "docx", "pdf", "xlsx", "txt", "md", "csv"));
        properties.add("source_content", ToolDefinition.stringProperty("本轮图片等新来源中提取出的内容；没有时留空"));
        properties.add("source_name", ToolDefinition.stringProperty("新来源的说明名称，例如用户图片；没有时留空"));
        this.definition = new ToolDefinition(
                NAME,
                "生成文档",
                "从零生成新的 DOCX/PDF/XLSX/TXT/MD/CSV 文件。不会自动使用会话中的旧文档；不要用于格式转换，也不生成 PPT/PPTX。",
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
        String outputType = DocumentFileType.canonical(
                ToolArguments.string(arguments, "output_type", "docx"));
        if (!DocumentFileType.canGenerate(outputType)) {
            return ToolResult.failure(DocumentFileType.isPresentation(outputType)
                    ? "当前支持识别和编辑 PPT/PPTX，但暂不支持从零生成演示文稿"
                    : "暂不支持生成 " + outputType + " 文件");
        }
        String sourceContent = ToolArguments.string(arguments, "source_content", "");
        String sourceName = ToolArguments.string(arguments, "source_name", "");

        String generationRequest = buildGenerationRequest(request, outputType, sourceContent, sourceName);
        String rawContent = documentAiService.generateDocument(context.userId(), generationRequest);
        if (rawContent == null || rawContent.isBlank()) {
            return ToolResult.failure("文档内容生成失败");
        }

        GeneratedDocument generated = parseGeneratedDocument(
                rawContent, fallbackTitle(sourceContent, request));
        String title = generated.title();
        String content = generated.content();
        byte[] bytes = switch (outputType) {
            case "txt", "md", "csv" -> documentService.createPlainText(content);
            case "xlsx" -> documentService.createXlsx(content);
            case "pdf" -> documentService.createPdf(title, content);
            default -> documentService.createDocx(title, content);
        };
        String fileName = outputFileName(outputType, title);
        return ToolResult.success("文件已生成",
                new DocumentToolOutput(bytes, outputType, fileName, content, "文件已生成"));
    }

    static String buildGenerationRequest(String request, String outputType,
                                         String sourceContent, String sourceName) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("用户要求：\n").append(request);
        if (sourceContent != null && !sourceContent.isBlank()) {
            prompt.append("\n\n本轮新来源：")
                    .append(sourceName == null || sourceName.isBlank() ? "用户刚发送的图片" : sourceName)
                    .append("\n以下内容是本次生成的唯一数据来源，禁止引用以前对话或旧文档：\n")
                    .append(sourceContent);
        }
        prompt.append("\n\n输出的第一行必须严格使用格式：[文件标题]具体标题。"
                + "标题应根据内容总结凝练，使用4到20个汉字，不要写扩展名，"
                + "禁止使用‘图片内容’、‘生成文件’、‘文档’、‘表格’等空泛名称。"
                + "从第二行开始输出实际文件内容。");
        if ("xlsx".equals(outputType)) {
            prompt.append("\n除第一行文件标题标记外，请只输出 Markdown 表格。"
                    + "表格第一行为表头，保留所有识别到的行列和数值，不要添加解释文字。");
        }
        return prompt.toString();
    }

    static GeneratedDocument parseGeneratedDocument(String rawContent, String fallbackTitle) {
        String[] lines = rawContent.strip().split("\\R", -1);
        String title = null;
        StringBuilder content = new StringBuilder();
        boolean titleRemoved = false;
        for (String line : lines) {
            String stripped = line.strip();
            if (!titleRemoved && (stripped.startsWith("[文件标题]")
                    || stripped.startsWith("文件标题：") || stripped.startsWith("文件标题:"))) {
                title = stripped.replaceFirst("^\\[?文件标题\\]?\\s*[:：]?\\s*", "");
                titleRemoved = true;
                continue;
            }
            if (!content.isEmpty()) content.append('\n');
            content.append(line);
        }
        String resolvedTitle = title == null || title.isBlank() ? fallbackTitle : title;
        resolvedTitle = compactTitle(resolvedTitle);
        String resolvedContent = content.toString().strip();
        if (resolvedContent.isBlank()) resolvedContent = rawContent.strip();
        return new GeneratedDocument(resolvedTitle, resolvedContent);
    }

    static String fallbackTitle(String sourceContent, String request) {
        if (sourceContent != null && !sourceContent.isBlank()) {
            for (String line : sourceContent.lines().toList()) {
                String candidate = line.strip()
                        .replaceFirst("^(摘要|图片摘要|内容摘要)\\s*[:：]\\s*", "")
                        .replaceAll("^[#>*|\\-\\s]+", "")
                        .replaceAll("[|]+", " ")
                        .strip();
                if (candidate.length() >= 2 && !candidate.equals("完整内容")) {
                    return compactTitle(candidate);
                }
            }
        }
        return compactTitle(request == null ? "生成文件" : request
                .replaceAll("(?:请|帮我|给我|根据|这张|图片|生成|制作|整理|一个|一份|文档|表格)", ""));
    }

    static String outputFileName(String outputType, String title) {
        String baseName = title == null || title.isBlank()
                ? "生成文件" : compactTitle(title);
        return baseName + "." + outputType;
    }

    private static String compactTitle(String value) {
        String baseName = value.replaceAll("(?i)\\.(docx|pdf|xlsx|pptx|txt|md|csv)$", "")
                .replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("[。！？；;]+$", "")
                .strip();
        if (baseName.length() > 24) baseName = baseName.substring(0, 24);
        return baseName.isBlank() ? "生成文件" : baseName;
    }

    record GeneratedDocument(String title, String content) {
    }
}
