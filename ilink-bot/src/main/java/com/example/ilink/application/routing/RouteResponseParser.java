package com.example.ilink.application.routing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;

import java.io.StringReader;

/** 从路由模型文本中提取 JSON 响应。 */
public final class RouteResponseParser {

    public JsonObject parseObject(String content) {
        String json = content.trim();
        if (json.startsWith("```")) {
            int firstLineEnd = json.indexOf('\n');
            int closingFence = json.lastIndexOf("```");
            if (firstLineEnd >= 0 && closingFence > firstLineEnd) {
                json = json.substring(firstLineEnd + 1, closingFence).trim();
            }
        }
        int firstObject = json.indexOf('{');
        int lastObject = json.lastIndexOf('}');
        if (firstObject < 0 || lastObject < firstObject) {
            throw new IllegalArgumentException("路由模型未返回 JSON 对象");
        }
        json = json.substring(firstObject, lastObject + 1);
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.LENIENT);
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("路由模型返回的不是 JSON 对象");
            }
            return parsed.getAsJsonObject();
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("路由模型 JSON 读取失败", error);
        }
    }
}
