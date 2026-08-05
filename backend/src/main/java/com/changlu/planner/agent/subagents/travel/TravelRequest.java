package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record TravelRequest(
    String destination,
    String origin,
    String startDate,
    String endDate,
    Integer travelers,
    JsonObject budget,
    String pace,
    JsonArray interests,
    JsonArray constraints
) {
  public static TravelRequest from(JsonObject value) {
    return new TravelRequest(text(value, "destination"), text(value, "origin"), text(value, "startDate"),
        text(value, "endDate"), integer(value, "travelers"), object(value, "budget"),
        text(value, "pace"), array(value, "interests"), array(value, "constraints"));
  }

  public static TravelRequest merge(TravelRequest previous, TravelRequest current) {
    if (previous == null) return current;
    if (current == null) return previous;
    return new TravelRequest(prefer(current.destination, previous.destination),
        prefer(current.origin, previous.origin), prefer(current.startDate, previous.startDate),
        prefer(current.endDate, previous.endDate), current.travelers == null ? previous.travelers : current.travelers,
        current.budget.isEmpty() ? previous.budget.deepCopy() : current.budget.deepCopy(),
        prefer(current.pace, previous.pace), current.interests.isEmpty()
            ? previous.interests.deepCopy() : current.interests.deepCopy(),
        current.constraints.isEmpty() ? previous.constraints.deepCopy() : current.constraints.deepCopy());
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("destination", destination);
    value.addProperty("origin", origin);
    value.addProperty("startDate", startDate);
    value.addProperty("endDate", endDate);
    if (travelers == null) value.add("travelers", null); else value.addProperty("travelers", travelers);
    value.add("budget", budget.deepCopy());
    value.addProperty("pace", pace);
    value.add("interests", interests.deepCopy());
    value.add("constraints", constraints.deepCopy());
    return value;
  }

  private static String text(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString().trim() : "";
  }
  private static Integer integer(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsInt() : null;
  }
  private static JsonObject object(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name).deepCopy() : new JsonObject();
  }
  private static JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name).deepCopy() : new JsonArray();
  }
  private static String prefer(String current, String previous) {
    return current == null || current.isBlank() ? previous : current;
  }
}
