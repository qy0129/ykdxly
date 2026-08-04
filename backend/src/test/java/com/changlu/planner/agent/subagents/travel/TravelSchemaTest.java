package com.changlu.planner.agent.subagents.travel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.runtime.JsonSchemaValidator;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

final class TravelSchemaTest {
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
    value.add("arguments", new JsonObject()); value.add("documentIds", new com.google.gson.JsonArray());
    assertThrows(IllegalArgumentException.class,
        () -> new JsonSchemaValidator().validate(value, schema("input.schema.json")));
  }

  private JsonObject schema(String name) {
    var stream = TravelSchemaTest.class.getResourceAsStream("/subagents/travel/" + name);
    if (stream == null) throw new IllegalStateException("missing schema: " + name);
    try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      return JsonParser.parseReader(reader).getAsJsonObject();
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }
}
