package com.changlu.planner.agent.core.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 主 Agent 循环的累积状态：步骤摘要、任务数据、迭代数、待确认草稿、用户回答。持久化为 agent_runs.state JSON。 */
public final class AgentLoopState {
  public static final int SCHEMA_VERSION = 1;

  public String goal = "";
  public final List<String> userTurns = new ArrayList<>();
  /** 紧凑步骤摘要，喂给路由器判断下一步。元素形如 {executorType, executorName, label, status, message}。 */
  public final JsonArray steps = new JsonArray();
  /** 已完成执行器的中间数据合并区，供后续执行器经 taskState 读取。 */
  public final JsonObject taskData = new JsonObject();
  public int iteration = 0;
  public String pendingDraftId;
  public final JsonArray pendingQuestions = new JsonArray();
  public final List<String> confirmedDrafts = new ArrayList<>();
  /** 整个 run 的墙钟截止时间（epoch millis），防止后台循环失控。 */
  public long deadlineEpochMs = 0;

  public boolean pastDeadline() {
    return deadlineEpochMs > 0 && Instant.now().toEpochMilli() > deadlineEpochMs;
  }

  public void appendStep(String executorType, String executorName, String label,
                         String status, String message) {
    JsonObject step = new JsonObject();
    step.addProperty("executorType", executorType);
    step.addProperty("executorName", executorName);
    step.addProperty("label", label);
    step.addProperty("status", status);
    if (message != null && !message.isBlank()) step.addProperty("message", compact(message));
    steps.add(step);
  }

  /** JsonArray 没有 clear()，用遍历移除清空待确认问题。 */
  public void clearPendingQuestions() {
    while (!pendingQuestions.isEmpty()) pendingQuestions.remove(0);
  }

  private static String compact(String value) {
    String normalized = value.replaceAll("\\s+", " ").trim();
    return normalized.length() <= 180 ? normalized : normalized.substring(0, 177) + "...";
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("schemaVersion", SCHEMA_VERSION);
    value.addProperty("goal", goal);
    JsonArray turns = new JsonArray();
    userTurns.forEach(turns::add);
    value.add("userTurns", turns);
    value.add("steps", steps.deepCopy());
    value.add("taskData", taskData.deepCopy());
    value.addProperty("iteration", iteration);
    if (pendingDraftId == null) value.add("pendingDraftId", com.google.gson.JsonNull.INSTANCE);
    else value.addProperty("pendingDraftId", pendingDraftId);
    value.add("pendingQuestions", pendingQuestions.deepCopy());
    JsonArray confirmed = new JsonArray();
    confirmedDrafts.forEach(confirmed::add);
    value.add("confirmedDrafts", confirmed);
    value.addProperty("deadlineEpochMs", deadlineEpochMs);
    return value;
  }

  public static AgentLoopState fromJson(JsonObject value) {
    AgentLoopState state = new AgentLoopState();
    if (value == null) return state;
    state.goal = string(value, "goal", "");
    if (value.has("userTurns") && value.get("userTurns").isJsonArray()) {
      for (JsonElement element : value.getAsJsonArray("userTurns")) {
        if (element.isJsonPrimitive()) state.userTurns.add(element.getAsString());
      }
    }
    if (value.has("steps") && value.get("steps").isJsonArray()) {
      for (JsonElement element : value.getAsJsonArray("steps")) {
        if (element.isJsonObject()) state.steps.add(element.deepCopy());
      }
    }
    if (value.has("taskData") && value.get("taskData").isJsonObject()) {
      for (String key : value.getAsJsonObject("taskData").keySet()) {
        state.taskData.add(key, value.getAsJsonObject("taskData").get(key).deepCopy());
      }
    }
    state.iteration = intValue(value, "iteration", 0);
    state.pendingDraftId = string(value, "pendingDraftId", null);
    if (value.has("pendingQuestions") && value.get("pendingQuestions").isJsonArray()) {
      state.pendingQuestions.addAll(value.getAsJsonArray("pendingQuestions").deepCopy());
    }
    if (value.has("confirmedDrafts") && value.get("confirmedDrafts").isJsonArray()) {
      for (JsonElement element : value.getAsJsonArray("confirmedDrafts")) {
        if (element.isJsonPrimitive()) state.confirmedDrafts.add(element.getAsString());
      }
    }
    state.deadlineEpochMs = longValue(value, "deadlineEpochMs", 0);
    return state;
  }

  private static String string(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }

  private static int intValue(JsonObject object, String name, int fallback) {
    return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsInt() : fallback;
  }

  private static long longValue(JsonObject object, String name, long fallback) {
    return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsLong() : fallback;
  }
}
