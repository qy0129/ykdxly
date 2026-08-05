package com.changlu.planner.agent.core.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.util.UUID;

/** Minimal dependency-free validator for the schema keywords used by agent contracts. */
public final class JsonSchemaValidator {
  public void validate(JsonObject value, JsonObject schema) {
    validateValue(value, schema, "input");
  }

  private void validateValue(JsonElement value, JsonObject schema, String path) {
    if (schema.has("enum")) {
      boolean matches = false;
      for (JsonElement option : schema.getAsJsonArray("enum")) if (option.equals(value)) matches = true;
      if (!matches) throw invalid(path);
    }
    if (schema.has("type") && !matchesType(value, schema.get("type"))) throw invalid(path);
    if (value == null || value.isJsonNull()) return;
    if (value.isJsonObject()) validateObject(value.getAsJsonObject(), schema, path);
    if (value.isJsonArray()) validateArray(value.getAsJsonArray(), schema, path);
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) validateString(value, schema, path);
    if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && schema.has("minimum")
        && value.getAsDouble() < schema.get("minimum").getAsDouble()) throw invalid(path);
  }

  private void validateObject(JsonObject value, JsonObject schema, String path) {
    if (schema.has("required")) {
      for (JsonElement required : schema.getAsJsonArray("required")) {
        if (!value.has(required.getAsString())) throw invalid(path + "." + required.getAsString());
      }
    }
    if (!schema.has("properties")) return;
    JsonObject properties = schema.getAsJsonObject("properties");
    for (String name : properties.keySet()) {
      if (value.has(name)) validateValue(value.get(name), properties.getAsJsonObject(name), path + "." + name);
    }
  }

  private void validateArray(JsonArray value, JsonObject schema, String path) {
    if (!schema.has("items")) return;
    for (int index = 0; index < value.size(); index++) {
      validateValue(value.get(index), schema.getAsJsonObject("items"), path + "[" + index + "]");
    }
  }

  private void validateString(JsonElement value, JsonObject schema, String path) {
    String text = value.getAsString();
    if (schema.has("minLength") && text.length() < schema.get("minLength").getAsInt()) throw invalid(path);
    // Optional form fields may be sent as an empty string while the UI is collecting them.
    if (text.isBlank()) return;
    if (!schema.has("format")) return;
    try {
      switch (schema.get("format").getAsString()) {
        case "date" -> LocalDate.parse(text);
        case "uuid" -> UUID.fromString(text);
        default -> { }
      }
    } catch (RuntimeException error) {
      throw invalid(path);
    }
  }

  private boolean matchesType(JsonElement value, JsonElement type) {
    if (type.isJsonArray()) {
      for (JsonElement option : type.getAsJsonArray()) if (matchesType(value, option)) return true;
      return false;
    }
    return switch (type.getAsString()) {
      case "object" -> value != null && value.isJsonObject();
      case "array" -> value != null && value.isJsonArray();
      case "string" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
      case "boolean" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
      case "number" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
      case "integer" -> value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()
          && value.getAsDouble() == Math.rint(value.getAsDouble());
      case "null" -> value == null || value.isJsonNull();
      default -> true;
    };
  }

  private IllegalArgumentException invalid(String path) {
    return new IllegalArgumentException("INVALID_ARGUMENT:" + path);
  }
}
