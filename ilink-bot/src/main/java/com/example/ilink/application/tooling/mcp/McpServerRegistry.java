package com.example.ilink.application.tooling.mcp;

import com.example.ilink.application.tooling.ToolManager;

import java.util.LinkedHashMap;
import java.util.Map;

/** 管理多个 MCP 服务的发现与内部工具注册。 */
public final class McpServerRegistry {
    private final Map<String, McpClient> clients = new LinkedHashMap<>();

    public McpServerRegistry register(String name, McpClient client) {
        if (clients.putIfAbsent(name, client) != null) throw new IllegalArgumentException("MCP 服务重复：" + name);
        return this;
    }

    public void installTools(ToolManager tools) throws Exception {
        for (Map.Entry<String, McpClient> entry : clients.entrySet()) {
            for (McpClient.McpTool tool : entry.getValue().listTools()) {
                tools.register(new McpToolAdapter(entry.getKey(), entry.getValue(), tool));
            }
        }
    }
}
