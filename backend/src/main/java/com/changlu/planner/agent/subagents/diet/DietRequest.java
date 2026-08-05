package com.changlu.planner.agent.subagents.diet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 输入领域记录。字段分级见设计 §5.1：
 * goal 与 profile 的五个字段（age/sex/heightCm/weightKg/activityLevel）影响营养目标计算结果，
 * 缺失时必须追问；其余字段为锦上添花，缺省跳过。
 */
public record DietRequest(
    String goal,
    JsonObject profile,
    String dietaryType,
    JsonArray allergies,
    JsonArray dislikes,
    JsonArray medicalConditions,
    Integer mealsPerDay,
    Integer cookTimeMinutes,
    JsonObject weeklyBudget,
    JsonArray preferences,
    Boolean saveToPlanner
) {
  public static DietRequest from(JsonObject value) {
    return new DietRequest(text(value, "goal"), object(value, "profile"), text(value, "dietaryType"),
        array(value, "allergies"), array(value, "dislikes"), array(value, "medicalConditions"),
        integer(value, "mealsPerDay"), integer(value, "cookTimeMinutes"), object(value, "weeklyBudget"),
        array(value, "preferences"), bool(value, "saveToPlanner"));
  }

  public JsonObject toJson() {
    JsonObject value = new JsonObject();
    value.addProperty("goal", goal);
    value.add("profile", profile.deepCopy());
    value.addProperty("dietaryType", dietaryType);
    value.add("allergies", allergies.deepCopy());
    value.add("dislikes", dislikes.deepCopy());
    value.add("medicalConditions", medicalConditions.deepCopy());
    if (mealsPerDay == null) value.add("mealsPerDay", com.google.gson.JsonNull.INSTANCE);
    else value.addProperty("mealsPerDay", mealsPerDay);
    if (cookTimeMinutes == null) value.add("cookTimeMinutes", com.google.gson.JsonNull.INSTANCE);
    else value.addProperty("cookTimeMinutes", cookTimeMinutes);
    value.add("weeklyBudget", weeklyBudget.deepCopy());
    value.add("preferences", preferences.deepCopy());
    if (saveToPlanner == null) value.add("saveToPlanner", com.google.gson.JsonNull.INSTANCE);
    else value.addProperty("saveToPlanner", saveToPlanner);
    return value;
  }

  /** 返回 profile 中某字段的数值，缺失时返回 null。 */
  public Double profileNumber(String name) {
    if (!profile.has(name) || profile.get(name).isJsonNull()
        || !profile.get(name).isJsonPrimitive()) return null;
    return profile.get(name).getAsDouble();
  }

  /** 返回 profile 中某字段的文本，缺失时返回空串。 */
  public String profileText(String name) {
    if (!profile.has(name) || profile.get(name).isJsonNull()
        || !profile.get(name).isJsonPrimitive() || !profile.get(name).getAsJsonPrimitive().isString()) {
      return "";
    }
    return profile.get(name).getAsString().trim();
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
