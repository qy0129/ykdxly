package com.example.ilink.application.tooling;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Objects;

/**
 * 一个符合 Function Calling 规范的函数工具定义。
 *
 * <p>核心字段与 OpenAI 官方规范一致：type、name、description、parameters
 * 和 strict。displayName 只用于本项目的中文控制台日志，不会发送给模型。</p>
 */
public record ToolDefinition(
        String name,
        String displayName,
        String description,
        JsonObject parameters,
        boolean strict) {

    /** 校验工具定义中的必填信息。 */
    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parameters, "parameters");
    }

    /** 转换为 Responses API 使用的标准函数工具结构。 */
    public JsonObject toFunctionTool() {
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        tool.add("parameters", parameters.deepCopy());
        tool.addProperty("strict", strict);
        return tool;
    }

    /** 转换为 Chat Completions API 使用的 function 嵌套结构。 */
    public JsonObject toChatCompletionsTool() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters.deepCopy());
        function.addProperty("strict", strict);

        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    /** 创建 strict 模式使用的对象参数结构。 */
    public static JsonObject objectParameters(JsonObject properties, String... requiredNames) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        for (String name : requiredNames) {
            required.add(name);
        }
        parameters.add("required", required);
        parameters.addProperty("additionalProperties", false);
        return parameters;
    }

    /** 创建字符串参数定义。 */
    public static JsonObject stringProperty(String description) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "string");
        property.addProperty("description", description);
        return property;
    }

    /** 创建带固定选项的字符串参数定义。 */
    public static JsonObject enumStringProperty(String description, String... values) {
        JsonObject property = stringProperty(description);
        JsonArray options = new JsonArray();
        for (String value : values) {
            options.add(value);
        }
        property.add("enum", options);
        return property;
    }

    /** 创建整数参数定义。 */
    public static JsonObject integerProperty(String description, int minimum, int maximum) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "integer");
        property.addProperty("description", description);
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        return property;
    }

    /** 创建数字参数定义，支持小数和金额。 */
    public static JsonObject numberProperty(String description, double minimum, double maximum) {
        JsonObject property = new JsonObject();
        property.addProperty("type", "number");
        property.addProperty("description", description);
        property.addProperty("minimum", minimum);
        property.addProperty("maximum", maximum);
        return property;
    }
}
