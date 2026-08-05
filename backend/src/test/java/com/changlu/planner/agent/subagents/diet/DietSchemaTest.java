package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
