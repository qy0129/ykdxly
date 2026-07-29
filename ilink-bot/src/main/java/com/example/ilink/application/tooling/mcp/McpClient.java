package com.example.ilink.application.tooling.mcp;

import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.util.List;

/** 通用 MCP 客户端契约，屏蔽不同服务商的 JSON-RPC 传输细节。 */
public interface McpClient {
    List<McpTool> listTools() throws Exception;

    ToolResult callTool(String toolName, JsonObject arguments) throws Exception;

    record McpTool(String name, String description, JsonObject inputSchema) { }
}
