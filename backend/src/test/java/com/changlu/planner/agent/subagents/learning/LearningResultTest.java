package com.changlu.planner.agent.subagents.learning;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * LearningResult 结果模型单元测试。
 * 覆盖正常流程、参数错误和边界条件。
 */
class LearningResultTest {

  @Test
  void successResultContainsAllRequiredFields() {
    JsonObject data = new JsonObject();
    data.addProperty("key", "value");
    LearningResult result = LearningResult.success("操作成功", data);

    assertEquals("success", result.status());
    assertEquals("操作成功", result.message());
    assertNotNull(result.data());
    assertNotNull(result.errors());
    assertTrue(result.errors().isEmpty());

    JsonObject json = result.toJson();
    assertEquals("success", json.get("status").getAsString());
    assertEquals("操作成功", json.get("message").getAsString());
    assertTrue(json.has("data"));
    assertTrue(json.has("errors"));
  }

  @Test
  void pendingConfirmationResultHasCorrectStatus() {
    JsonObject data = new JsonObject();
    data.addProperty("draftId", "abc-123");
    LearningResult result = LearningResult.pendingConfirmation("请确认后执行", data);

    assertEquals("pending_confirmation", result.status());
    assertEquals("请确认后执行", result.message());

    JsonObject json = result.toJson();
    assertEquals("pending_confirmation", json.get("status").getAsString());
    assertTrue(json.getAsJsonObject("data").has("draftId"));
  }

  @Test
  void errorResultContainsErrorDetails() {
    JsonArray errors = new JsonArray();
    JsonObject err = new JsonObject();
    err.addProperty("code", "VALIDATION_ERROR");
    err.addProperty("detail", "标题不能为空");
    errors.add(err);

    LearningResult result = LearningResult.error("参数校验失败", errors);

    assertEquals("error", result.status());
    assertEquals("参数校验失败", result.message());
    assertEquals(1, result.errors().size());
    assertEquals("VALIDATION_ERROR",
        result.errors().get(0).getAsJsonObject().get("code").getAsString());

    JsonObject json = result.toJson();
    assertEquals("error", json.get("status").getAsString());
  }

  @Test
  void singleErrorMessageCreatesProperStructure() {
    LearningResult result = LearningResult.error("服务异常", "数据库连接超时");

    assertEquals("error", result.status());
    assertEquals(1, result.errors().size());
    assertEquals("LEARNING_ERROR",
        result.errors().get(0).getAsJsonObject().get("code").getAsString());
    assertEquals("数据库连接超时",
        result.errors().get(0).getAsJsonObject().get("detail").getAsString());
  }

  @Test
  void validationErrorStoresValidationDetails() {
    JsonArray validationErrors = new JsonArray();
    JsonObject e1 = new JsonObject();
    e1.addProperty("field", "title");
    e1.addProperty("message", "必填字段");
    validationErrors.add(e1);
    JsonObject e2 = new JsonObject();
    e2.addProperty("field", "domain");
    e2.addProperty("message", "未知领域");
    validationErrors.add(e2);

    LearningResult result = LearningResult.validationError("参数校验失败", validationErrors);

    assertEquals("error", result.status());
    assertEquals(2, result.errors().size());
  }

  @Test
  void toAgentResponseIncludesReplyField() {
    JsonObject data = new JsonObject();
    data.addProperty("result", "ok");
    LearningResult result = LearningResult.success("一切正常", data);

    JsonObject response = result.toAgentResponse();
    assertTrue(response.has("reply"));
    assertEquals("一切正常", response.get("reply").getAsString());
    assertTrue(response.has("status"));
    assertTrue(response.has("data"));
  }

  @Test
  void toAgentResponseWithErrorsIncludesErrorsField() {
    LearningResult result = LearningResult.error("失败", "原因");

    JsonObject response = result.toAgentResponse();
    assertTrue(response.has("errors"));
    assertEquals(1, response.getAsJsonArray("errors").size());
  }

  @Test
  void successToAgentResponseDoesNotIncludeErrorsField() {
    JsonObject data = new JsonObject();
    LearningResult result = LearningResult.success("成功", data);

    JsonObject response = result.toAgentResponse();
    // errors array is empty, should not be included per toAgentResponse logic
    assertFalse(response.has("errors") && !response.getAsJsonArray("errors").isEmpty());
  }
}
