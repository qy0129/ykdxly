package com.example.ilink.application.tooling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashSet;
import java.util.Set;

/** 对模型或业务代码传入工具的 JSON 参数执行轻量运行时 Schema 校验。 */
public final class ToolSchemaValidator {
    public Result validate(JsonObject schema, JsonObject arguments) {
        if (schema == null || arguments == null) return Result.fail("工具参数必须是 JSON 对象");
        return validateObject(schema, arguments, "$ ");
    }

    private Result validateObject(JsonObject schema, JsonObject value, String path) {
        JsonArray required = array(schema, "required");
        for (JsonElement item : required) {
            String name = item.getAsString();
            if (!value.has(name) || value.get(name).isJsonNull()) {
                return Result.fail(path + "." + name + " 为必填参数");
            }
        }
        if (booleanValue(schema, "additionalProperties", true) == false) {
            Set<String> allowed = new HashSet<>();
            JsonObject properties = object(schema, "properties");
            if (properties != null) allowed.addAll(properties.keySet());
            for (String name : value.keySet()) {
                if (!allowed.contains(name)) return Result.fail(path + "." + name + " 不是允许的参数");
            }
        }
        JsonObject properties = object(schema, "properties");
        if (properties == null) return Result.ok();
        for (var entry : value.entrySet()) {
            JsonObject property = object(properties, entry.getKey());
            if (property == null) continue;
            Result result = validateValue(property, entry.getValue(), path + "." + entry.getKey());
            if (!result.valid()) return result;
        }
        return Result.ok();
    }

    private Result validateValue(JsonObject schema, JsonElement value, String path) {
        if (value == null || value.isJsonNull()) return Result.fail(path + " 不能为 null");
        String type = text(schema, "type");
        if ("string".equals(type) && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())) return Result.fail(path + " 必须是字符串");
        if ("integer".equals(type)) {
            if (!value.isJsonPrimitive()) return Result.fail(path + " 必须是整数");
            try {
                int number = value.getAsInt();
                if (schema.has("minimum") && number < schema.get("minimum").getAsInt()) return Result.fail(path + " 小于最小值");
                if (schema.has("maximum") && number > schema.get("maximum").getAsInt()) return Result.fail(path + " 大于最大值");
            } catch (RuntimeException error) { return Result.fail(path + " 必须是整数"); }
        }
        if ("number".equals(type)) {
            try { value.getAsBigDecimal(); } catch (RuntimeException error) { return Result.fail(path + " 必须是数字"); }
        }
        if ("boolean".equals(type) && (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())) {
            return Result.fail(path + " 必须是布尔值");
        }
        if ("object".equals(type) && !value.isJsonObject()) return Result.fail(path + " 必须是对象");
        if ("array".equals(type) && !value.isJsonArray()) return Result.fail(path + " 必须是数组");
        JsonArray enumValues = array(schema, "enum");
        if (!enumValues.isEmpty()) {
            boolean found = false;
            for (JsonElement item : enumValues) {
                if (item.toString().equals(value.toString())) {
                    found = true;
                    break;
                }
            }
            if (!found) return Result.fail(path + " 不在允许的枚举值中");
        }
        if (value.isJsonObject() && "object".equals(type)) return validateObject(schema, value.getAsJsonObject(), path);
        return Result.ok();
    }

    private JsonObject object(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonObject() ? source.getAsJsonObject(name) : null;
    }
    private JsonArray array(JsonObject source, String name) {
        return source.has(name) && source.get(name).isJsonArray() ? source.getAsJsonArray(name) : new JsonArray();
    }
    private String text(JsonObject source, String name) {
        return source.has(name) && !source.get(name).isJsonNull() ? source.get(name).getAsString() : "";
    }
    private boolean booleanValue(JsonObject source, String name, boolean fallback) {
        return source.has(name) ? source.get(name).getAsBoolean() : fallback;
    }

    public record Result(boolean valid, String message) {
        static Result ok() { return new Result(true, ""); }
        static Result fail(String message) { return new Result(false, message); }
    }
}
