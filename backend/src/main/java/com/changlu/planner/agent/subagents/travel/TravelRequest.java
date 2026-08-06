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
    JsonArray constraints,
    JsonObject deviceLocation,
    String preferredTransport,
    Integer hotelStarRating,
    Boolean avoidEarlyMorning,
    Boolean elderlyTravel,
    Boolean beachPreference
) {
  public static TravelRequest from(JsonObject value) {
    return new TravelRequest(text(value, "destination"), text(value, "origin"), text(value, "startDate"),
        text(value, "endDate"), integer(value, "travelers"), object(value, "budget"),
        text(value, "pace"), array(value, "interests"), array(value, "constraints"),
        object(value, "deviceLocation"), text(value, "preferredTransport"),
        integer(value, "hotelStarRating"), bool(value, "avoidEarlyMorning"),
        bool(value, "elderlyTravel"), bool(value, "beachPreference"));
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
    value.add("deviceLocation", deviceLocation.deepCopy());
    // The input schema treats transport as optional. Do not serialize the
    // internal empty-string default because it is not a valid enum value.
    if (preferredTransport != null && !preferredTransport.isBlank()) {
      value.addProperty("preferredTransport", preferredTransport);
    }
    if (hotelStarRating == null) value.add("hotelStarRating", null);
    else value.addProperty("hotelStarRating", hotelStarRating);
    addBoolean(value, "avoidEarlyMorning", avoidEarlyMorning);
    addBoolean(value, "elderlyTravel", elderlyTravel);
    addBoolean(value, "beachPreference", beachPreference);
    return value;
  }

  private static void addBoolean(JsonObject value, String name, Boolean field) {
    if (field == null) value.add(name, null); else value.addProperty(name, field);
  }

  private static String text(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString().trim() : "";
  }
  private static Integer integer(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsInt() : null;
  }
  private static Boolean bool(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsBoolean() : null;
  }
  private static JsonObject object(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name).deepCopy() : new JsonObject();
  }
  private static JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name).deepCopy() : new JsonArray();
  }
}
