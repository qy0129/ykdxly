package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public record TravelResult(
    String message,
    TravelRequest request,
    JsonArray days,
    JsonArray preparationTasks,
    JsonObject budgetEstimate,
    JsonArray sources,
    JsonArray risks,
    JsonArray questions,
    String planningInstruction
) {
  public static TravelResult fromGenerated(JsonObject generated, JsonArray sources) {
    return new TravelResult(text(generated, "message", "已整理旅行方案。"),
        TravelRequest.from(object(generated, "request")), array(generated, "days"),
        array(generated, "preparationTasks"), object(generated, "budgetEstimate"), sources.deepCopy(),
        array(generated, "risks"), array(generated, "questions"), text(generated, "planningInstruction", ""));
  }

  public JsonObject toData() {
    JsonObject value = new JsonObject();
    value.add("request", request.toJson());
    value.add("days", days.deepCopy());
    value.add("preparationTasks", preparationTasks.deepCopy());
    value.add("budgetEstimate", budgetEstimate.deepCopy());
    value.add("sources", sources.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("questions", questions.deepCopy());
    value.addProperty("planningInstruction", planningInstruction);
    return value;
  }

  public TravelResult withRequestAndQuestions(TravelRequest mergedRequest, JsonArray mergedQuestions) {
    return new TravelResult(message, mergedRequest, days, preparationTasks, budgetEstimate, sources, risks,
        mergedQuestions, planningInstruction);
  }

  private static String text(JsonObject value, String name, String fallback) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : fallback;
  }
  private static JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name).deepCopy() : new JsonArray();
  }
  private static JsonObject object(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name).deepCopy() : new JsonObject();
  }
}
