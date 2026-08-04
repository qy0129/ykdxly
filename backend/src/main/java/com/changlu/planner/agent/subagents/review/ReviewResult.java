package com.changlu.planner.agent.subagents.review;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.LocalDate;

/** 复盘的结构化结果，同时负责 JSON 边界转换和本地降级。 */
public record ReviewResult(
    String date,
    JsonObject facts,
    String summary,
    JsonArray highlights,
    JsonArray risks,
    JsonArray nextActions,
    String generatedAt,
    boolean aiGenerated
) {
  public static ReviewResult fromGenerated(JsonObject facts, JsonObject generated, String generatedAt) {
    return new ReviewResult(
        text(facts, "date", LocalDate.now().toString()),
        facts,
        text(generated, "summary", "今天还没有足够的执行记录可供复盘。"),
        array(generated, "highlights"),
        array(generated, "risks"),
        array(generated, "nextActions"),
        generatedAt,
        true);
  }

  public static ReviewResult fromCache(JsonObject facts, String summary, JsonObject suggestions,
                                       String generatedAt) {
    return new ReviewResult(
        text(facts, "date", LocalDate.now().toString()),
        facts,
        summary,
        array(suggestions, "highlights"),
        array(suggestions, "risks"),
        array(suggestions, "nextActions"),
        generatedAt,
        !suggestions.has("aiGenerated") || suggestions.get("aiGenerated").getAsBoolean());
  }

  public static ReviewResult fallback(JsonObject facts, String generatedAt) {
    int completed = facts.get("completedTasks").getAsInt() + facts.get("completed").getAsInt()
        + facts.get("scheduleCompleted").getAsInt();
    int delayed = facts.get("delayed").getAsInt();
    int blocked = facts.get("blocked").getAsInt();
    String summary = completed == 0
        ? "今天还没有已确认的完成记录。先选一项最重要且能在短时间内完成的任务，建立今天的第一个进展。"
        : "今天共留下 " + completed + " 项完成记录。"
            + (delayed + blocked == 0 ? "执行过程暂未出现明显的延期或阻塞。"
                : "同时有 " + delayed + " 项延期、" + blocked + " 项阻塞，需要优先处理原因。");
    JsonArray highlights = new JsonArray();
    if (completed > 0) highlights.add("完成了 " + completed + " 项已确认事项");
    JsonArray risks = new JsonArray();
    if (delayed > 0) risks.add("有 " + delayed + " 项延期");
    if (blocked > 0) risks.add("有 " + blocked + " 项阻塞");
    JsonArray nextActions = new JsonArray();
    nextActions.add(completed == 0 ? "选择一项 30 分钟内可完成的任务并开始" : "从未完成事项中确认明天最重要的一项");
    if (delayed + blocked > 0) nextActions.add("逐项补充延期或阻塞原因");
    return new ReviewResult(text(facts, "date", LocalDate.now().toString()), facts, summary,
        highlights, risks, nextActions, generatedAt, false);
  }

  public JsonObject suggestions() {
    JsonObject value = new JsonObject();
    value.add("highlights", highlights.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("nextActions", nextActions.deepCopy());
    value.addProperty("aiGenerated", aiGenerated);
    return value;
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("date", date);
    value.add("facts", facts.deepCopy());
    value.addProperty("summary", summary);
    value.add("highlights", highlights.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("nextActions", nextActions.deepCopy());
    value.addProperty("generatedAt", generatedAt);
    value.addProperty("aiGenerated", aiGenerated);
    return value;
  }

  private static JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray()
        ? object.getAsJsonArray(name).deepCopy() : new JsonArray();
  }

  private static String text(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }
}
