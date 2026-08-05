package com.changlu.planner.agent.subagents.learning;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 学习规划 Subagent 的统一结果模型。
 * 所有输出遵循 {status, message, data, errors} 结构，
 * 字段命名统一，避免返回无法解析的自由文本。
 */
public record LearningResult(
    String status,       // success | pending_confirmation | error
    String message,      // 人类可读的摘要
    JsonObject data,     // 领域特定数据
    JsonArray errors     // 错误详情列表
) {
  /** 成功结果（只读查询）。 */
  public static LearningResult success(String message, JsonObject data) {
    JsonArray errors = new JsonArray();
    return new LearningResult("success", message, data, errors);
  }

  /** 待确认结果（写操作草案）。 */
  public static LearningResult pendingConfirmation(String message, JsonObject data) {
    JsonArray errors = new JsonArray();
    return new LearningResult("pending_confirmation", message, data, errors);
  }

  public static LearningResult waitingUser(String message, JsonObject data) {
    return new LearningResult("waiting_user", message, data, new JsonArray());
  }

  /** 错误结果。 */
  public static LearningResult error(String message, JsonArray errors) {
    return new LearningResult("error", message, new JsonObject(), errors);
  }

  /** 单个错误的便捷构造。 */
  public static LearningResult error(String message, String errorDetail) {
    JsonArray errors = new JsonArray();
    JsonObject err = new JsonObject();
    err.addProperty("code", "LEARNING_ERROR");
    err.addProperty("detail", errorDetail);
    errors.add(err);
    return new LearningResult("error", message, new JsonObject(), errors);
  }

  /** 参数校验错误。 */
  public static LearningResult validationError(String message, JsonArray validationErrors) {
    return new LearningResult("error", message, new JsonObject(), validationErrors);
  }

  public JsonObject toJson() {
    JsonObject result = new JsonObject();
    result.addProperty("status", status);
    result.addProperty("message", message);
    result.add("data", data != null ? data.deepCopy() : new JsonObject());
    result.add("errors", errors != null ? errors.deepCopy() : new JsonArray());
    return result;
  }

  /** 在 Subagent.execute() 返回时使用的简短格式（兼容现有 Agent 协议）。 */
  public JsonObject toAgentResponse() {
    JsonObject result = new JsonObject();
    result.addProperty("status", status);
    result.addProperty("reply", message);
    result.add("data", data != null ? data.deepCopy() : new JsonObject());
    if (errors != null && !errors.isEmpty()) {
      result.add("errors", errors.deepCopy());
    }
    return result;
  }
}
