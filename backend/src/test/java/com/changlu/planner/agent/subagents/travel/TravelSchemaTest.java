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

  @Test void travelRequestRoundTripPreservesExtendedFields() {
    JsonObject input = JsonParser.parseString("""
        {"destination":"青岛","deviceLocation":{"lat":36.1,"lng":120.3,"permission":"granted"},
        "preferredTransport":"highSpeedRail","hotelStarRating":4,"avoidEarlyMorning":true,
        "elderlyTravel":true,"beachPreference":true}
        """).getAsJsonObject();
    JsonObject output = TravelRequest.from(input).toJson();
    assertEquals("highSpeedRail", output.get("preferredTransport").getAsString());
    assertEquals(4, output.get("hotelStarRating").getAsInt());
    assertTrue(output.get("avoidEarlyMorning").getAsBoolean());
    assertEquals("granted", output.getAsJsonObject("deviceLocation").get("permission").getAsString());
  }

  @Test void travelRequestOmitsOptionalBlankTransport() {
    JsonObject output = TravelRequest.from(JsonParser.parseString(""
        + "{\"destination\":\"青岛\",\"startDate\":\"2026-09-01\",\"endDate\":\"2026-09-03\","
        + "\"preferredTransport\":\"\"}").getAsJsonObject()).toJson();

    assertTrue(!output.has("preferredTransport"));
    JsonObject input = new JsonObject();
    input.addProperty("message", "确认行程");
    JsonObject arguments = new JsonObject();
    arguments.addProperty("destination", output.get("destination").getAsString());
    arguments.addProperty("startDate", output.get("startDate").getAsString());
    arguments.addProperty("endDate", output.get("endDate").getAsString());
    input.add("arguments", arguments);
    input.add("documentIds", new com.google.gson.JsonArray());
    new JsonSchemaValidator().validate(input, schema("input.schema.json"));
  }

  @Test void travelResultAlwaysContainsExternalFactContainers() {
    TravelResult result = TravelResult.fromGenerated(new JsonObject(), new com.google.gson.JsonArray());
    JsonObject data = result.toData();
    assertTrue(data.get("locationContext").isJsonObject());
    assertTrue(data.get("weather").isJsonArray());
    assertTrue(data.get("attractions").isJsonArray());
    assertTrue(data.get("transitMatrix").isJsonArray());
    assertTrue(data.get("alternativePlans").isJsonArray());
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
