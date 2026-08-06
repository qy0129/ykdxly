package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.runtime.JsonSchemaValidator;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** input/output Schema 校验、版本与变更（设计 §6 / §11：DietSchemaTest）。 */
final class DietSchemaTest {
  @Test void schemasUseDraft202012AndDeclareRequiredFields() {
    JsonObject input = schema("input.schema.json");
    JsonObject output = schema("output.schema.json");

    assertTrue(input.get("$schema").getAsString().contains("2020-12"));
    assertTrue(output.get("$schema").getAsString().contains("2020-12"));
    assertTrue(input.getAsJsonArray("required").contains(JsonParser.parseString("\"message\"")));
    assertTrue(output.getAsJsonArray("required").contains(JsonParser.parseString("\"status\"")));
    assertEquals(5, output.getAsJsonObject("properties").getAsJsonObject("status")
        .getAsJsonArray("enum").size());
  }

  @Test void standardResultUsesLowercaseStatusAndJsonNullDraft() {
    JsonObject result = AgentResult.completed("完成", new JsonObject(), "trace").toJson();
    assertEquals("completed", result.get("status").getAsString());
    assertTrue(result.get("draftId").isJsonNull());
  }

  @Test void inputSchemaRejectsMissingRequiredMessage() {
    JsonObject value = new JsonObject();
    value.add("arguments", new JsonObject()); value.add("documentIds", new JsonArray());
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchemaValidator().validate(value, schema("input.schema.json")));
  }

  @Test void inputSchemaEnforcesProfileAndEnumConstraints() {
    JsonObject value = schemaInput();
    value.getAsJsonObject("arguments").getAsJsonObject("profile").addProperty("age", 17);
    // age < 18 由 Schema 下限兜底（设计 §8.2）
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchemaValidator().validate(value, schema("input.schema.json")));

    JsonObject dietary = schemaInput();
    dietary.getAsJsonObject("arguments").addProperty("dietaryType", "paleo");
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchemaValidator().validate(dietary, schema("input.schema.json")));
  }

  @Test void dietaryTypeEnumHasSixOptionsAndBudgetRequiresCurrency() {
    JsonObject input = schema("input.schema.json");
    JsonObject properties = input.getAsJsonObject("properties").getAsJsonObject("arguments")
        .getAsJsonObject("properties");
    assertEquals(6, properties.getAsJsonObject("dietaryType").getAsJsonArray("enum").size());
    assertTrue(properties.getAsJsonObject("weeklyBudget").getAsJsonArray("required")
        .contains(JsonParser.parseString("\"currency\"")));
  }

  @Test void emptyOptionalFieldsAreOmittedFromSerializedRequest() {
    // 回归：WAITING_USER 第一轮（全字段缺失）的 DietRequest.toJson 之前会序列化 dietaryType:""、
    // mealsPerDay:null、weeklyBudget:{} 等，resume 回放 taskData.request 时 input.schema 校验直接 INVALID_ARGUMENT。
    // 现在这些空可选字段必须被跳过，完整参数能通过 input.schema 校验。
    JsonObject persisted = DietRequest.from(new JsonObject()).toJson();
    assertFalse(persisted.has("dietaryType"));
    assertFalse(persisted.has("mealsPerDay"));
    assertFalse(persisted.has("weeklyBudget"));
    assertFalse(persisted.has("saveToPlanner"));
    assertFalse(persisted.has("profile"));
    assertTrue(persisted.has("goal"));

    JsonObject value = new JsonObject();
    value.addProperty("message", "帮我做个减脂餐计划");
    value.add("arguments", persisted);
    value.add("documentIds", new JsonArray());
    new JsonSchemaValidator().validate(value, schema("input.schema.json"));
  }

  @Test void sanitizeDropsHallucinatedFieldsAndRoundTripsThroughSchema() {
    // 提取器幻觉非法 dietaryType 与越界 age：sanitize 删除后，toJson 的结果必须能通过 input.schema 校验
    // （对应第二轮 resume 的崩溃路径：input.arguments.dietaryType / profile.age 超范围）。
    JsonObject arguments = new JsonObject();
    arguments.addProperty("goal", "减脂");
    arguments.addProperty("dietaryType", "paleo");
    JsonObject profile = new JsonObject();
    profile.addProperty("age", 200); profile.addProperty("sex", "male");
    profile.addProperty("activityLevel", "moderate");
    arguments.add("profile", profile);
    new DietPolicy().sanitize(arguments);

    JsonObject persisted = DietRequest.from(arguments).toJson();
    assertFalse(persisted.has("dietaryType"));
    assertFalse(persisted.getAsJsonObject("profile").has("age"));
    assertEquals("male", persisted.getAsJsonObject("profile").get("sex").getAsString());

    JsonObject value = new JsonObject();
    value.addProperty("message", "帮我做个减脂餐计划");
    value.add("arguments", persisted);
    value.add("documentIds", new JsonArray());
    new JsonSchemaValidator().validate(value, schema("input.schema.json"));
  }

  @Test void fillProfileFromContextRecoversMissingProfileFromMemoryAndUserLines() {
    // 确定性兜底：提取器是 LLM 偶发不产出 profile，必须能从记忆段 + [用户] 发言解析出用户本人资料，
    // 否则 requiredFields 会反复追问用户已给过的信息。
    JsonObject arguments = new JsonObject();
    arguments.addProperty("goal", "减脂");
    String shared = "用户长期记忆（稳定的偏好、个性和事实，请自然遵循）：\n"
        + "- [personal_fact] 用户男性，21岁，178cm，65kg\n\n"
        + "最近对话（用于理解上下文；当前请求会单独提供）：\n"
        + "[用户] 帮我做个减脂餐计划\n"
        + "[用户] 男，21岁，体重65kg，身高178cm，办公久坐\n"
        + "[AI] 已生成饮食计划。";
    new DietPolicy().fillProfileFromContext(arguments, shared);

    JsonObject profile = arguments.getAsJsonObject("profile");
    assertEquals(21.0, profile.get("age").getAsDouble());
    assertEquals("male", profile.get("sex").getAsString());
    assertEquals(178.0, profile.get("heightCm").getAsDouble());
    assertEquals(65.0, profile.get("weightKg").getAsDouble());
    assertEquals("sedentary", profile.get("activityLevel").getAsString());
  }

  @Test void fillGoalFromContextPrefersCurrentMessageThenFallsBackToMemory() {
    // 当前消息优先（用户最新意图为准），消息无目标才回退记忆；已有 goal 不覆盖。
    JsonObject fromMessage = new JsonObject();
    new DietPolicy().fillGoalFromContext(fromMessage, "把这份减脂餐计划保存到我的计划，并排进日程", "记忆里：增肌");
    assertEquals("减脂", fromMessage.get("goal").getAsString());

    JsonObject fromMemory = new JsonObject();
    new DietPolicy().fillGoalFromContext(fromMemory, "帮我安排饮食", "用户长期记忆：想减脂");
    assertEquals("减脂", fromMemory.get("goal").getAsString());

    JsonObject fromAnswer = new JsonObject();
    new DietPolicy().fillGoalFromContext(fromAnswer, "保持健康", "记忆里：减脂");
    assertEquals("保持健康", fromAnswer.get("goal").getAsString()); // 当前答案覆盖旧目标

    JsonObject existing = new JsonObject();
    existing.addProperty("goal", "增肌");
    new DietPolicy().fillGoalFromContext(existing, "减脂", "记忆：减脂");
    assertEquals("增肌", existing.get("goal").getAsString()); // 已有目标不被覆盖
  }

  @Test void fillProfileFromContextKeepsExistingAndIgnoresAiContent() {
    // 已有字段不被覆盖；AI 回复里的数字（如他人体重）不被误取。
    JsonObject arguments = new JsonObject();
    arguments.addProperty("goal", "减脂");
    JsonObject existing = new JsonObject();
    existing.addProperty("age", 22); existing.addProperty("sex", "male");
    arguments.add("profile", existing);
    String shared = "用户长期记忆（稳定的偏好、个性和事实，请自然遵循）：\n"
        + "- [personal_fact] 用户男性，21岁，178cm，65kg，办公久坐\n\n"
        + "最近对话：\n[AI] 已为你生成一周菜单，参考体重 55kg 计算。\n[用户] 好的";
    new DietPolicy().fillProfileFromContext(arguments, shared);

    JsonObject profile = arguments.getAsJsonObject("profile");
    assertEquals(22.0, profile.get("age").getAsDouble());   // 已有 age 不被覆盖
    assertEquals("male", profile.get("sex").getAsString()); // 已有 sex 不被覆盖
    // 缺失的 heightCm/weightKg/activityLevel 从记忆补全；AI 回复里的 55kg 不读取
    assertTrue(profile.has("heightCm"));
    assertTrue(profile.has("weightKg"));
    assertTrue(profile.has("activityLevel"));
    assertEquals(65.0, profile.get("weightKg").getAsDouble());
    assertEquals("sedentary", profile.get("activityLevel").getAsString());
  }

  private JsonObject schemaInput() {
    JsonObject arguments = new JsonObject();
    JsonObject profile = new JsonObject();
    profile.addProperty("age", 30); profile.addProperty("sex", "male");
    profile.addProperty("heightCm", 175); profile.addProperty("weightKg", 70);
    profile.addProperty("activityLevel", "moderate");
    arguments.add("profile", profile);
    JsonObject value = new JsonObject();
    value.addProperty("message", "安排一周减脂餐");
    value.add("arguments", arguments);
    value.add("documentIds", new JsonArray());
    return value;
  }

  private JsonObject schema(String name) {
    var stream = DietSchemaTest.class.getResourceAsStream("/subagents/diet/" + name);
    if (stream == null) throw new IllegalStateException("missing schema: " + name);
    try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
