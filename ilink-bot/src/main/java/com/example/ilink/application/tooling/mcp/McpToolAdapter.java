package com.example.ilink.application.tooling.mcp;

import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

/** 将远程 MCP Tool 适配为系统内部 Tool，使 Agent Loop 无需区分来源。 */
public final class McpToolAdapter implements Tool {
    private final McpClient client;
    private final McpClient.McpTool tool;
    private final ToolDefinition definition;

    public McpToolAdapter(String serverName, McpClient client, McpClient.McpTool tool) {
        this.client = client;
        this.tool = tool;
        String name = ("mcp_" + serverName + "_" + tool.name()).replaceAll("[^a-zA-Z0-9_]", "_");
        JsonObject schema = tool.inputSchema() == null ? new JsonObject() : tool.inputSchema().deepCopy();
        if (!schema.has("type")) schema.addProperty("type", "object");
        if (!schema.has("properties")) schema.add("properties", new JsonObject());
        if (!schema.has("additionalProperties")) schema.addProperty("additionalProperties", true);
        this.definition = new ToolDefinition(name, "MCP " + tool.name(), tool.description(), schema, false);
    }

    @Override
    public ToolDefinition definition() {
        return definition;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) throws Exception {
        return client.callTool(tool.name(), arguments);
    }
}
