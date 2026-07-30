package com.example.ilink.application.tooling;

import com.example.ilink.application.messaging.RequestLogContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具注册、查找和统一分发中心。
 *
 * <p>所有工具在应用启动时注册。调用方只需要提供工具英文名称和参数，
 * 不需要了解具体实现类。</p>
 */
public final class ToolManager {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /** 注册一个工具，禁止重复名称覆盖已有工具。 */
    public ToolManager register(Tool tool) {
        ToolDefinition definition = tool.definition();
        if (tools.putIfAbsent(definition.name(), tool) != null) {
            throw new IllegalArgumentException("工具名称重复: " + definition.name());
        }
        System.out.println("[工具管理] 已注册：" + definition.displayName()
                + "（" + definition.name() + "）");
        return this;
    }

    /** 按英文名称查找工具，找不到时返回 null。 */
    public Tool find(String name) {
        return tools.get(name);
    }

    /** 返回已注册工具的只读集合。 */
    public Collection<Tool> allTools() {
        return java.util.List.copyOf(tools.values());
    }

    /** 输出 Responses API 可直接使用的工具定义数组。 */
    public JsonArray functionDefinitions() {
        JsonArray definitions = new JsonArray();
        for (Tool tool : tools.values()) {
            definitions.add(tool.definition().toFunctionTool());
        }
        return definitions;
    }

    /** 输出 Chat Completions API 可直接使用的工具定义数组。 */
    public JsonArray chatCompletionsDefinitions() {
        JsonArray definitions = new JsonArray();
        for (Tool tool : tools.values()) {
            definitions.add(tool.definition().toChatCompletionsTool());
        }
        return definitions;
    }

    /** 查找并执行工具，同时输出统一的中文调用日志。 */
    public ToolResult execute(String name, ToolContext context, JsonObject arguments) {
        Tool tool = find(name);
        if (tool == null) {
            System.err.println(RequestLogContext.prefix("工具") + " missing=" + name);
            return ToolResult.failure("未找到工具：" + name);
        }

        ToolDefinition definition = tool.definition();
        System.out.println(RequestLogContext.prefix("工具") + " name=" + definition.name()
                + " label=" + definition.displayName()
                + " args=" + RequestLogContext.preview(arguments == null ? "{}" : arguments.toString()));
        try {
            ToolResult result = tool.execute(context, arguments);
            String status = result.success() ? "成功" : "失败";
            System.out.println(RequestLogContext.prefix("工具结果") + " name=" + definition.name()
                    + " status=" + status);
            return result;
        } catch (Exception e) {
            System.err.println(RequestLogContext.prefix("工具结果") + " name=" + definition.name()
                    + " status=失败 error=" + RequestLogContext.error(e));
            return ToolResult.failure(e.getMessage());
        }
    }
}
