package com.changlu.planner.agent.subagents.research;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** 网页研究的结构化输出。 */
public record ResearchResult(List<WebSearchTool.Result> materials) {
  public String reply() {
    if (materials.isEmpty()) return "暂时没有找到可靠的公开网页结果。";
    StringBuilder value = new StringBuilder();
    for (int index = 0; index < materials.size(); index++) {
      WebSearchTool.Result item = materials.get(index);
      value.append(index + 1).append(". ").append(item.title());
      if (!item.summary().isBlank()) value.append("\n").append(item.summary());
      value.append("\n").append(item.url()).append("\n\n");
    }
    return value.toString().trim();
  }

  public JsonObject toJson() {
    JsonArray rows = new JsonArray();
    for (WebSearchTool.Result item : materials) {
      JsonObject row = new JsonObject();
      row.addProperty("title", item.title());
      row.addProperty("summary", item.summary());
      row.addProperty("source", item.source());
      row.addProperty("url", item.url());
      rows.add(row);
    }
    JsonObject result = new JsonObject();
    result.addProperty("reply", reply());
    result.add("materials", rows);
    return result;
  }
}
