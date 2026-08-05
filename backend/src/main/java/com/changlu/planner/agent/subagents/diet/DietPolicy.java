package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 领域安全策略（设计 §2 / §5.1 / §8）：
 * <ul>
 *   <li>输入校验：参数类型、枚举与取值范围，缺失字段不报错（由 requiredFields 追问）；</li>
 *   <li>不支持检测：治疗/诊断/药物/处方与极端节食关键词直接拒绝；</li>
 *   <li>医学风险筛查：未成年人、孕妇、哺乳期直接拒绝；慢性病只加强制风险标注并提示就医；</li>
 *   <li>结果校验：信息完整时必须有确定性营养目标与菜单。</li>
 * </ul>
 */
public final class DietPolicy {
  private static final Set<String> DIETARY_TYPES =
      Set.of("balanced", "vegetarian", "vegan", "halal", "pescatarian", "none");
  private static final Set<String> ACTIVITY_LEVELS =
      Set.of("sedentary", "light", "moderate", "active", "very_active");

  /** 输入参数类型 / 枚举 / 取值范围校验。缺失即跳过（可选字段或待追问字段）。 */
  public void validateInput(SubagentRequest request) {
    if (request.message().isBlank()) throw new IllegalArgumentException("INVALID_ARGUMENT:message");
    JsonObject arguments = request.arguments();
    requireType(arguments, "goal", "string");
    requireType(arguments, "dietaryType", "string");
    requireType(arguments, "saveToPlanner", "boolean");
    validateProfile(arguments);
    if (arguments.has("dietaryType")
        && !DIETARY_TYPES.contains(arguments.get("dietaryType").getAsString())) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:dietaryType");
    }
    validateStringArray(arguments, "allergies");
    validateStringArray(arguments, "dislikes");
    validateStringArray(arguments, "medicalConditions");
    validateStringArray(arguments, "preferences");
    validateIntegerRange(arguments, "mealsPerDay", 2, 6);
    validateIntegerRange(arguments, "cookTimeMinutes", 0, 180);
    validateBudget(arguments);
    for (String documentId : request.documentIds()) {
      try { UUID.fromString(documentId); }
      catch (IllegalArgumentException error) { throw new IllegalArgumentException("INVALID_ARGUMENT:documentIds"); }
    }
  }

  private void validateProfile(JsonObject arguments) {
    if (!arguments.has("profile")) return;
    if (!arguments.get("profile").isJsonObject()) throw new IllegalArgumentException("INVALID_ARGUMENT:profile");
    JsonObject profile = arguments.getAsJsonObject("profile");
    requireType(profile, "age", "number");
    requireType(profile, "sex", "string");
    requireType(profile, "heightCm", "number");
    requireType(profile, "weightKg", "number");
    requireType(profile, "targetWeightKg", "number");
    requireType(profile, "activityLevel", "string");
    if (profile.has("age")) {
      double age = profile.get("age").getAsDouble();
      if (age != Math.rint(age)) throw new IllegalArgumentException("INVALID_ARGUMENT:profile.age");
      if (age > 120) throw new IllegalArgumentException("INVALID_ARGUMENT:profile.age");
      // age < 18 不在此处报 INVALID_ARGUMENT：属于未成年人医学拒绝，由 unsupportedProfile 返回
      // DIET_MEDICAL_UNSUPPORTED（设计 §8.1/§8.2，Schema 下限 18 兜底）。
    }
    if (profile.has("sex") && !Set.of("male", "female").contains(profile.get("sex").getAsString())) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:profile.sex");
    }
    if (profile.has("heightCm")
        && (profile.get("heightCm").getAsDouble() < 100 || profile.get("heightCm").getAsDouble() > 250)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:profile.heightCm");
    }
    if (profile.has("weightKg")
        && (profile.get("weightKg").getAsDouble() < 30 || profile.get("weightKg").getAsDouble() > 300)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:profile.weightKg");
    }
    if (profile.has("targetWeightKg")
        && (profile.get("targetWeightKg").getAsDouble() < 30 || profile.get("targetWeightKg").getAsDouble() > 300)) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:profile.targetWeightKg");
    }
    if (profile.has("activityLevel")
        && !ACTIVITY_LEVELS.contains(profile.get("activityLevel").getAsString())) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:profile.activityLevel");
    }
  }

  /** 医疗 / 极端节食关键词命中 → 拒绝（设计 §2.2 / §8.1）。 */
  public boolean unsupportedRequest(String message) {
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("治疗") || normalized.contains("诊断")
        || normalized.contains("药物") || normalized.contains("处方")
        || normalized.contains("药方") || normalized.contains("开药")
        || normalized.contains("化疗") || normalized.contains("手术") || normalized.contains("绝食")
        || normalized.contains("断食") || normalized.contains("只喝水")
        || normalized.contains("代替医生") || normalized.contains("营养师开");
  }

  /** 未成年人（age &lt; 18）与孕妇 / 哺乳期（medicalConditions 命中）→ 拒绝（设计 §2.2 / §8.1）。 */
  public boolean unsupportedProfile(DietRequest request) {
    Double age = request.profileNumber("age");
    if (age != null && age < 18) return true;
    for (String condition : stringValues(request.medicalConditions())) {
      String normalized = condition.replaceAll("\\s", "");
      if (normalized.contains("孕妇") || normalized.contains("怀孕") || normalized.contains("妊娠")
          || normalized.contains("哺乳") || normalized.contains("备孕")) {
        return true;
      }
    }
    return false;
  }

  /** 慢性病 / 服药人群：不拒绝，返回强制风险标注并提示就医（设计 §3 medicalRiskScreen）。 */
  public JsonArray medicalRiskScreen(DietRequest request) {
    JsonArray risks = new JsonArray();
    for (String condition : stringValues(request.medicalConditions())) {
      String normalized = condition.replaceAll("\\s", "");
      if (chronicCondition(normalized) || normalized.contains("服药") || normalized.contains("用药")
          || normalized.contains("吃药")) {
        JsonObject risk = new JsonObject();
        risk.addProperty("code", "DIET_MEDICAL_SCREENING");
        risk.addProperty("message", "你提到" + condition.trim() + "，方案已按保守结构生成，"
            + "建议咨询医生或注册营养师后再执行。");
        risks.add(risk);
      }
    }
    return risks;
  }

  /** 影响计算结果的必需字段缺失列表（设计 §5.1 字段分级）。 */
  public List<String> requiredFields(DietRequest request) {
    List<String> missing = new ArrayList<>();
    if (request.goal().isBlank()) missing.add("目标（减脂/增肌/保持健康/控糖/均衡营养）");
    if (request.profileNumber("age") == null) missing.add("年龄");
    if (request.profileText("sex").isBlank()) missing.add("性别");
    if (request.profileNumber("heightCm") == null) missing.add("身高");
    if (request.profileNumber("weightKg") == null) missing.add("体重");
    if (request.profileText("activityLevel").isBlank()) missing.add("日常活动量");
    return List.copyOf(missing);
  }

  /** 结果校验：没有追问时必须有确定性营养目标与菜单；菜单每餐必须落到具体食物。 */
  public void validate(DietResult result) {
    if (result.questions().size() == 0 && result.dailyTargets().size() == 0) {
      throw new IllegalArgumentException("DIET_TARGETS_REQUIRED");
    }
    if (result.questions().size() == 0 && result.mealPlan().size() == 0) {
      throw new IllegalArgumentException("DIET_MEAL_PLAN_REQUIRED");
    }
    if (result.questions().isEmpty()) {
      for (var day : result.mealPlan()) {
        if (!day.isJsonObject()) continue;
        JsonArray meals = day.getAsJsonObject().has("meals")
            && day.getAsJsonObject().get("meals").isJsonArray()
            ? day.getAsJsonObject().getAsJsonArray("meals") : new JsonArray();
        for (var meal : meals) {
          if (!meal.isJsonObject()) continue;
          JsonObject value = meal.getAsJsonObject();
          JsonArray foods = value.has("foodItems") && value.get("foodItems").isJsonArray()
              ? value.getAsJsonArray("foodItems") : new JsonArray();
          if (foods.isEmpty()) throw new IllegalArgumentException("DIET_FOOD_ITEMS_REQUIRED");
          boolean hasConcrete = false;
          for (var food : foods) {
            if (!food.isJsonPrimitive() || !food.getAsJsonPrimitive().isString()) continue;
            String item = food.getAsString().trim();
            if (!item.isBlank() && item.length() >= 2) { hasConcrete = true; break; }
          }
          if (!hasConcrete) throw new IllegalArgumentException("DIET_FOOD_ITEMS_REQUIRED");
        }
      }
    }
  }

  /** 用户是否明确要求把方案写入计划 App（设计 §5.1 saveToPlanner，默认 false）。 */
  public boolean writeRequested(String message, JsonObject arguments) {
    if (arguments.has("saveToPlanner") && arguments.get("saveToPlanner").isJsonPrimitive()) {
      return arguments.get("saveToPlanner").getAsBoolean();
    }
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    if (normalized.contains("不要保存") || normalized.contains("只要建议") || normalized.contains("仅预览")) {
      return false;
    }
    // 「制定/创建/安排」等词既可能指"生成菜单"，也可能指"写入计划App"，单独出现不构成写入意图，
    // 避免把只想要一份菜单的用户误判为要写库（否则会触发不必要的草案生成、放大失败面）。
    // 只有出现明确的写入/保存意向时才返回 true。
    if (normalized.contains("保存到我的计划") || normalized.contains("写入我的计划")
        || normalized.contains("加入我的计划") || normalized.contains("保存到计划")
        || normalized.contains("写入计划") || normalized.contains("加入计划")
        || normalized.contains("生成计划并保存")) {
      return true;
    }
    return false;
  }

  /** 敏感信息（身高体重/过敏原/忌口）不进入错误详情；错误码只暴露原因，不携带个人信息。 */
  public static JsonObject emptyDetails() {
    return new JsonObject();
  }

  private boolean chronicCondition(String normalized) {
    return normalized.contains("糖尿病") || normalized.contains("高血压") || normalized.contains("高血脂")
        || normalized.contains("高胆固醇") || normalized.contains("痛风") || normalized.contains("肾病")
        || normalized.contains("肝病") || normalized.contains("心脏病") || normalized.contains("冠心病")
        || normalized.contains("甲亢") || normalized.contains("甲减") || normalized.contains("骨质疏松")
        || normalized.contains("胃炎") || normalized.contains("溃疡");
  }

  private List<String> stringValues(JsonArray array) {
    List<String> values = new ArrayList<>();
    for (var element : array) {
      if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
        values.add(element.getAsString());
      }
    }
    return values;
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

  private void validateIntegerRange(JsonObject arguments, String name, int minimum, int maximum) {
    requireType(arguments, name, "number");
    if (!arguments.has(name)) return;
    double value = arguments.get(name).getAsDouble();
    if (value != Math.rint(value) || value < minimum || value > maximum) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:" + name);
    }
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
    if (!arguments.has("weeklyBudget") || arguments.get("weeklyBudget").isJsonNull()) return;
    if (!arguments.get("weeklyBudget").isJsonObject()) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:weeklyBudget");
    }
    JsonObject budget = arguments.getAsJsonObject("weeklyBudget");
    if (!budget.has("amount") || !budget.has("currency")
        || !budget.get("amount").isJsonPrimitive() || !budget.getAsJsonPrimitive("amount").isNumber()
        || budget.get("amount").getAsDouble() < 0
        || !budget.get("currency").isJsonPrimitive() || !budget.getAsJsonPrimitive("currency").isString()
        || budget.get("currency").getAsString().length() != 3) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:weeklyBudget");
    }
  }
}
