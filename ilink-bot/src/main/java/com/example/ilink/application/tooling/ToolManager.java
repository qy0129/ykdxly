package com.example.ilink.application.tooling;

import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.messaging.ConsoleLog;
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
    private final ToolSchemaValidator schemaValidator = new ToolSchemaValidator();

    /** 注册一个工具，禁止重复名称覆盖已有工具。 */
    public ToolManager register(Tool tool) {
        ToolDefinition definition = tool.definition();
        if (tools.putIfAbsent(definition.name(), tool) != null) {
            throw new IllegalArgumentException("工具名称重复: " + definition.name());
        }
        ConsoleLog.info("工具管理", "已注册工具：" + definition.displayName()
                + "，工具编号=" + definition.name());
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
            ConsoleLog.warn("工具调用", "未找到工具，工具编号=" + name);
            return ToolResult.failure("未找到工具：" + name);
        }

        ToolDefinition definition = tool.definition();
        long startedAt = System.nanoTime();
        RequestLogContext.publish(new AgentEvent(AgentEvent.Type.TOOL_ACTIVITY,
                "调用工具：" + definition.displayName(), Map.of(
                "toolName", definition.name(), "toolLabel", definition.displayName(),
                "status", "running", "phase", "tool")));
        ConsoleLog.info("工具调用", "开始调用工具：" + definition.displayName()
                + "，工具编号=" + definition.name()
                + "，参数摘要=" + ConsoleLog.summary(arguments == null ? "{}" : arguments.toString()));
        try {
            ToolSchemaValidator.Result schemaResult = schemaValidator.validate(definition.parameters(), arguments);
            if (!schemaResult.valid()) {
                ConsoleLog.warn("工具参数校验", "工具=" + definition.displayName() + "，校验失败："
                        + schemaResult.message());
                return ToolResult.failure("工具参数无效：" + schemaResult.message());
            }
            ToolResult result = tool.execute(context, arguments);
            String status = result.success() ? "成功" : "失败";
            RequestLogContext.publish(new AgentEvent(AgentEvent.Type.TOOL_ACTIVITY,
                    (result.success() ? "工具完成：" : "工具失败：") + definition.displayName(), Map.of(
                    "toolName", definition.name(), "toolLabel", definition.displayName(),
                    "status", result.success() ? "success" : "failed", "phase", "tool")));
            ConsoleLog.info("工具结果", "工具=" + definition.displayName() + "，执行状态=" + status
                    + "，耗时=" + elapsedMillis(startedAt) + "毫秒，结果摘要=" + ConsoleLog.summary(result.output()));
            return result;
        } catch (Exception e) {
            RequestLogContext.publish(new AgentEvent(AgentEvent.Type.TOOL_ACTIVITY,
                    "工具失败：" + definition.displayName(), Map.of(
                    "toolName", definition.name(), "toolLabel", definition.displayName(),
                    "status", "failed", "phase", "tool")));
            ConsoleLog.error("工具结果", "工具=" + definition.displayName() + "，执行状态=失败，耗时="
                    + elapsedMillis(startedAt) + "毫秒，" + ConsoleLog.errorSummary(e));
            return ToolResult.failure(e.getMessage());
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
