package com.changlu.planner.agent.subagents.image;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 文生图输入/输出 JSON Schema 的加载入口。 */
public final class ImageSchema {
  private ImageSchema() {}

  /** 从 classpath /subagents/image/{name} 加载 schema，缺失直接抛错，避免运行时静默降级。 */
  public static JsonObject load(String name) {
    String path = "/subagents/image/" + name;
    try (InputStream input = ImageSchema.class.getResourceAsStream(path)) {
      if (input == null) throw new IllegalStateException("缺少 Image Schema：" + path);
      return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException("无法读取 Image Schema：" + path, error);
    }
  }
}