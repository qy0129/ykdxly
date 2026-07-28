package com.example.ilink.application.tooling;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.math.BigDecimal;

/** 统一读取和校验模型生成的工具参数。 */
public final class ToolArguments {

    private ToolArguments() {
    }

    /** 读取必填字符串。 */
    public static String requireString(JsonObject arguments, String name) {
        String value = string(arguments, name, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException("缺少工具参数: " + name);
        }
        return value;
    }

    /** 读取字符串，不存在时返回默认值。 */
    public static String string(JsonObject arguments, String name, String defaultValue) {
        JsonElement value = arguments.get(name);
        return value == null || value.isJsonNull() ? defaultValue : value.getAsString();
    }

    /** 读取整数，不存在时返回默认值。 */
    public static int integer(JsonObject arguments, String name, int defaultValue) {
        JsonElement value = arguments.get(name);
        return value == null || value.isJsonNull() ? defaultValue : value.getAsInt();
    }

    /** 读取数字参数，使用 BigDecimal 保留金额精度。 */
    public static BigDecimal decimal(JsonObject arguments, String name, BigDecimal defaultValue) {
        JsonElement value = arguments.get(name);
        return value == null || value.isJsonNull() ? defaultValue : value.getAsBigDecimal();
    }
}
