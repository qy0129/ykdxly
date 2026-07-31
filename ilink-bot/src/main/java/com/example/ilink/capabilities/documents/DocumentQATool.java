package com.example.ilink.capabilities.documents;

import com.example.ilink.application.conversation.DocumentSessionStore;
import com.example.ilink.capabilities.documents.DocumentAiService;
import com.example.ilink.capabilities.documents.DocumentRecord;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 文档问答和总结工具。 */
public final class DocumentQATool implements Tool {

    public static final String NAME = "ask_document";

    private final DocumentAiService documentAiService;
    private final DocumentSessionStore documentSessions;
    private final ToolDefinition definition;

    /** 创建文档问答工具。 */
    public DocumentQATool(DocumentAiService documentAiService,
                          DocumentSessionStore documentSessions) {
        this.documentAiService = documentAiService;
        this.documentSessions = documentSessions;

        JsonObject properties = new JsonObject();
        properties.add("request", ToolDefinition.stringProperty("针对当前文档的完整问题或总结要求"));
        properties.add("action", ToolDefinition.enumStringProperty(
                "question 表示问答，summary 表示总结", "question", "summary"));
        this.definition = new ToolDefinition(
                NAME,
                "文档问答",
                "根据用户最近发送的文档回答问题或总结内容。没有当前文档时不要调用。",
                ToolDefinition.objectParameters(properties, "request", "action"),
                true);
    }

    /** 返回文档问答工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 读取当前文档并调用文档 AI 服务。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String action = ToolArguments.string(arguments, "action", "question");
        String request = ToolArguments.requireString(arguments, "request");
        DocumentRecord document = documentSessions.resolve(context.userId(), request);
        if (document == null) {
            return ToolResult.failure("请先发送需要处理的文档");
        }
        if ("summary".equals(action)) {
            request = "请总结这份文件，提炼核心观点、重要事实和结论。用户补充要求：" + request;
        }
        String answer = documentAiService.chatWithDocument(
                context.userId(), request, document.fileName(), document.text());
        return answer == null || answer.isBlank()
                ? ToolResult.failure("文档处理失败")
                : ToolResult.success(answer, document);
    }
}
