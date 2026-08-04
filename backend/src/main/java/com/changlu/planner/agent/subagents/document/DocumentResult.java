package com.changlu.planner.agent.subagents.document;

import com.google.gson.JsonObject;
import java.util.UUID;

/** 文件解析和索引完成后返回给网页的结构化结果。 */
public record DocumentResult(
    UUID id,
    String fileName,
    String extension,
    int extractedChars,
    int chunkCount,
    boolean vectorIndexed,
    boolean duplicate,
    String preview
) {
  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("id", id.toString());
    value.addProperty("fileName", fileName);
    value.addProperty("extension", extension);
    value.addProperty("extractedChars", extractedChars);
    value.addProperty("chunkCount", chunkCount);
    value.addProperty("vectorIndexed", vectorIndexed);
    value.addProperty("duplicate", duplicate);
    value.addProperty("preview", preview);
    return value;
  }
}
