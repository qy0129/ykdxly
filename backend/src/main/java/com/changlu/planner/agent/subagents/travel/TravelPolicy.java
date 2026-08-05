package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonObject;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Set;
import java.util.UUID;

public final class TravelPolicy {
  public void validateInput(SubagentRequest request) {
    if (request.message().isBlank()) throw new IllegalArgumentException("INVALID_ARGUMENT:message");
    JsonObject arguments = request.arguments();
    requireType(arguments, "destination", "string");
    requireType(arguments, "origin", "string");
    requireType(arguments, "travelers", "number");
    requireType(arguments, "saveToPlanner", "boolean");
    requireType(arguments, "pace", "string");
    validateDate(arguments, "startDate");
    validateDate(arguments, "endDate");
    if (arguments.has("travelers")) {
      double travelers = arguments.get("travelers").getAsDouble();
      if (travelers < 1 || travelers != Math.rint(travelers)) {
        throw new IllegalArgumentException("TRAVEL_TRAVELERS_INVALID");
      }
    }
    validateStringArray(arguments, "interests");
    validateStringArray(arguments, "constraints");
    validateBudget(arguments);
    if (arguments.has("pace")
        && !Set.of("relaxed", "balanced", "intensive").contains(arguments.get("pace").getAsString())) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:pace");
    }
    for (String documentId : request.documentIds()) {
      try { UUID.fromString(documentId); }
      catch (IllegalArgumentException error) { throw new IllegalArgumentException("INVALID_ARGUMENT:documentIds"); }
    }
  }

  public boolean unsupportedRequest(String message) {
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("代我订票") || normalized.contains("帮我订票")
        || normalized.contains("直接订票") || normalized.contains("替我付款")
        || normalized.contains("帮我付款") || normalized.contains("代订酒店")
        || normalized.contains("直接预订酒店") || normalized.contains("代订门票");
  }

  public boolean writeRequested(String message, JsonObject arguments) {
    if (arguments.has("saveToPlanner") && arguments.get("saveToPlanner").isJsonPrimitive()) {
      return arguments.get("saveToPlanner").getAsBoolean();
    }
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    if (normalized.contains("不要保存") || normalized.contains("只要建议") || normalized.contains("仅预览")) return false;
    return normalized.contains("制定") || normalized.contains("创建") || normalized.contains("安排")
        || normalized.contains("生成计划") || normalized.contains("加入计划") || normalized.contains("保存到");
  }

  public void validate(TravelResult result) {
    TravelRequest request = result.request();
    if (!request.startDate().isBlank() && !request.endDate().isBlank()
        && LocalDate.parse(request.endDate()).isBefore(LocalDate.parse(request.startDate()))) {
      throw new IllegalArgumentException("TRAVEL_DATE_RANGE_INVALID");
    }
    if (request.travelers() != null && request.travelers() < 1) {
      throw new IllegalArgumentException("TRAVEL_TRAVELERS_INVALID");
    }
    if (result.questions().isEmpty() && request.destination().isBlank()) {
      throw new IllegalArgumentException("TRAVEL_DESTINATION_REQUIRED");
    }
  }

  private void requireType(JsonObject arguments, String name, String expected) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return;
    boolean valid = switch (expected) {
      case "string" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isString();
      case "number" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isNumber();
      case "boolean" -> arguments.get(name).isJsonPrimitive()
          && arguments.get(name).getAsJsonPrimitive().isBoolean();
      default -> false;
    };
    if (!valid) throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
  }

  private void validateDate(JsonObject arguments, String name) {
    requireType(arguments, name, "string");
    if (!arguments.has(name) || arguments.get(name).getAsString().isBlank()) return;
    try { LocalDate.parse(arguments.get(name).getAsString()); }
    catch (DateTimeParseException error) { throw new IllegalArgumentException("INVALID_ARGUMENT:" + name); }
  }

  private void validateStringArray(JsonObject arguments, String name) {
    if (!arguments.has(name) || arguments.get(name).isJsonNull()) return;
    if (!arguments.get(name).isJsonArray()) throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
    arguments.getAsJsonArray(name).forEach(value -> {
      if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
        throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
      }
    });
  }

  private void validateBudget(JsonObject arguments) {
    if (!arguments.has("budget") || arguments.get("budget").isJsonNull()) return;
    if (!arguments.get("budget").isJsonObject()) throw new IllegalArgumentException("INVALID_ARGUMENT:budget");
    JsonObject budget = arguments.getAsJsonObject("budget");
    if (!budget.has("amount") || !budget.has("currency")
        || !budget.get("amount").isJsonPrimitive() || !budget.getAsJsonPrimitive("amount").isNumber()
        || budget.get("amount").getAsDouble() < 0
        || !budget.get("currency").isJsonPrimitive() || !budget.getAsJsonPrimitive("currency").isString()
        || budget.get("currency").getAsString().length() != 3) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:budget");
    }
  }
}
