package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
  /** 从 sharedContext 兜底解析用户本人资料的确定性正则（见 fillProfileFromContext）。 */
  private static final java.util.regex.Pattern USER_LINE =
      java.util.regex.Pattern.compile("\\[用户\\]\\s*(.+)");
  private static final java.util.regex.Pattern AGE_IN_TEXT =
      java.util.regex.Pattern.compile("(\\d{1,3})\\s*岁");
  private static final java.util.regex.Pattern HEIGHT_IN_TEXT =
      java.util.regex.Pattern.compile("身高[:：]?\\s*(\\d{2,3})|(\\d{2,3})\\s*(?:cm|厘米)");
  private static final java.util.regex.Pattern WEIGHT_IN_TEXT =
      java.util.regex.Pattern.compile("体重[:：]?\\s*(\\d{2,3})|(\\d{2,3})\\s*(?:kg|公斤|千克)");
  private static final java.util.regex.Pattern WEEKLY_TIMES =
      java.util.regex.Pattern.compile("每周[^\\d]{0,4}([1-7])");

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

  /**
   * 清洗合并/提取后的参数（设计 §5.1 / §8.4 防御）：Diet 参数提取器是模型，偶尔会幻觉枚举、越界数值或畸形嵌套对象。
   * 直接删除非法字段并视同未提供——既避免后续 schema 校验 / validateInput 抛 INVALID_ARGUMENT 中断整个请求，
   * 也避免非法值被写入 taskData.request 在 WAITING_USER 回放时二次触发崩溃。不修改任何合法参数。
   */
  public void sanitize(JsonObject arguments) {
    if (arguments == null) return;
    removeIfInvalidEnum(arguments, "dietaryType", DIETARY_TYPES);
    removeIfInvalidNumber(arguments, "mealsPerDay", 2, 6, true);
    removeIfInvalidNumber(arguments, "cookTimeMinutes", 0, 180, true);
    if (arguments.has("weeklyBudget")) {
      JsonElement budget = arguments.get("weeklyBudget");
      if (!budget.isJsonObject() || !validBudget(budget.getAsJsonObject())) arguments.remove("weeklyBudget");
    }
    if (!arguments.has("profile") || !arguments.get("profile").isJsonObject()) return;
    JsonObject profile = arguments.getAsJsonObject("profile");
    removeIfInvalidEnum(profile, "sex", Set.of("male", "female"));
    removeIfInvalidEnum(profile, "activityLevel", ACTIVITY_LEVELS);
    removeIfInvalidNumber(profile, "age", 18, 120, true);
    removeIfInvalidNumber(profile, "heightCm", 100, 250, false);
    removeIfInvalidNumber(profile, "weightKg", 30, 300, false);
    removeIfInvalidNumber(profile, "targetWeightKg", 30, 300, false);
  }

  /**
   * 补齐所有能从上下文确定性恢复的必需字段（目标 + profile）：
   * 参数提取器是 LLM，偶发漏提取 goal 或记忆里的 profile，导致 requiredFields 反复追问同一批字段
   * （"已知您为男性21岁…请确认"或"确认目标是减脂还是增肌"）。此兜底不依赖 LLM。
   */
  public void fillMissingFromContext(JsonObject arguments, String message, String sharedContext) {
    fillGoalFromContext(arguments, message, sharedContext);
    fillProfileFromContext(arguments, sharedContext);
  }

  /**
   * 目标兜底：先解析当前消息（用户最新意图优先），再回退到记忆/最近对话；已有 goal 不覆盖。
   */
  public void fillGoalFromContext(JsonObject arguments, String message, String sharedContext) {
    if (arguments == null) return;
    if (arguments.has("goal") && arguments.get("goal").isJsonPrimitive()
        && !arguments.get("goal").getAsString().isBlank()) {
      return;
    }
    String goal = detectGoal(message);
    if (goal.isBlank()) goal = detectGoal(sharedContext);
    if (!goal.isBlank()) arguments.addProperty("goal", goal);
  }

  private String detectGoal(String text) {
    if (text == null || text.isBlank()) return "";
    if (containsAny(text, "减脂", "减肥", "减重", "瘦身")) return "减脂";
    if (containsAny(text, "增肌", "健身")) return "增肌";
    if (containsAny(text, "控糖", "血糖")) return "控糖";
    if (containsAny(text, "保持健康", "均衡营养", "健康饮食")) return "保持健康";
    return "";
  }

  /**
   * 从长期记忆/最近对话中确定性补全缺失的 profile 必需字段（防御设计 §5.1 / §8.4）：
   * Diet 参数提取器是 LLM，偶发不把记忆里的用户资料转成结构化 profile，导致 requiredFields 反复追问同一批字段。
   * 此方法只填充仍缺失的字段，且只从「用户长期记忆段」与「[用户] 本人发言行」解析，
   * 不读 AI 回复、不读他人内容，避免误取；解析不到就保持缺失（照常追问一次）。
   */
  public void fillProfileFromContext(JsonObject arguments, String sharedContext) {
    if (arguments == null || sharedContext == null || sharedContext.isBlank()) return;
    JsonObject profile = arguments.has("profile") && arguments.get("profile").isJsonObject()
        ? arguments.getAsJsonObject("profile") : null;
    if (profile != null && profile.has("age") && profile.has("sex") && profile.has("heightCm")
        && profile.has("weightKg") && profile.has("activityLevel")) {
      return;
    }
    StringBuilder candidates = new StringBuilder();
    int divider = sharedContext.indexOf("最近对话");
    if (divider >= 0) candidates.append(sharedContext, 0, divider); else candidates.append(sharedContext);
    java.util.regex.Matcher userLine = USER_LINE.matcher(sharedContext);
    while (userLine.find()) candidates.append('\n').append(userLine.group(1));
    String text = candidates.toString();
    if (text.isBlank()) return;
    if (profile == null) profile = new JsonObject();
    if (!profile.has("age")) fillNumber(profile, "age", AGE_IN_TEXT, text);
    if (!profile.has("sex")) fillSex(profile, text);
    if (!profile.has("heightCm")) fillNumber(profile, "heightCm", HEIGHT_IN_TEXT, text);
    if (!profile.has("weightKg")) fillNumber(profile, "weightKg", WEIGHT_IN_TEXT, text);
    if (!profile.has("activityLevel")) fillActivity(profile, text);
    if (profile.size() > 0) arguments.add("profile", profile);
  }

  private void fillNumber(JsonObject profile, String name, java.util.regex.Pattern pattern, String text) {
    java.util.regex.Matcher matcher = pattern.matcher(text);
    if (!matcher.find()) return;
    String group = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
    if (group == null) return;
    try { profile.addProperty(name, Double.parseDouble(group.trim())); }
    catch (NumberFormatException ignored) { }
  }

  private void fillSex(JsonObject profile, String text) {
    int male = text.indexOf("男");
    int female = text.indexOf("女");
    if (male >= 0 && (female < 0 || male < female)) profile.addProperty("sex", "male");
    else if (female >= 0) profile.addProperty("sex", "female");
  }

  private void fillActivity(JsonObject profile, String text) {
    if (containsAny(text, "久坐", "办公", "几乎不运动", "不怎么运动", "少动", "不运动")) {
      profile.addProperty("activityLevel", "sedentary");
      return;
    }
    java.util.regex.Matcher weekly = WEEKLY_TIMES.matcher(text);
    if (weekly.find()) {
      profile.addProperty("activityLevel",
          Integer.parseInt(weekly.group(1)) >= 3 ? "moderate" : "light");
      return;
    }
    if (containsAny(text, "高强度", "每天训练", "每天运动", "大量运动", "健身")) {
      profile.addProperty("activityLevel", "active");
    } else if (containsAny(text, "剧烈", "职业运动员", "专业运动员")) {
      profile.addProperty("activityLevel", "very_active");
    } else if (containsAny(text, "中度", "每周三次", "每周三到五次")) {
      profile.addProperty("activityLevel", "moderate");
    } else if (containsAny(text, "轻度", "偶尔", "偶尔运动")) {
      profile.addProperty("activityLevel", "light");
    }
  }

  private boolean containsAny(String text, String... keywords) {
    for (String keyword : keywords) if (text.contains(keyword)) return true;
    return false;
  }

  private void removeIfInvalidEnum(JsonObject object, String name, Set<String> allowed) {
    if (!object.has(name) || object.get(name).isJsonNull()) return;
    JsonElement element = object.get(name);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
        || !allowed.contains(element.getAsString())) {
      object.remove(name);
    }
  }

  private void removeIfInvalidNumber(JsonObject object, String name, double minimum, double maximum, boolean integerOnly) {
    if (!object.has(name) || object.get(name).isJsonNull()) return;
    JsonElement element = object.get(name);
    if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) { object.remove(name); return; }
    double value = element.getAsDouble();
    if ((integerOnly && value != Math.rint(value)) || value < minimum || value > maximum) object.remove(name);
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

  /** 原文级别的安全拦截：孕妇/哺乳等表述可能在参数提取时丢失，直接按消息文本拦截（设计 §8.1）。 */
  public boolean unsupportedMessageText(String message) {
    String normalized = message == null ? "" : message.replaceAll("\\s", "");
    return normalized.contains("孕妇") || normalized.contains("怀孕") || normalized.contains("妊娠")
        || normalized.contains("哺乳") || normalized.contains("备孕")
        || normalized.contains("未成年人") || normalized.contains("未成年");
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
    if (!arguments.get("weeklyBudget").isJsonObject()
        || !validBudget(arguments.getAsJsonObject("weeklyBudget"))) {
      throw new IllegalArgumentException("INVALID_ARGUMENT:weeklyBudget");
    }
  }

  private boolean validBudget(JsonObject budget) {
    if (!budget.has("amount") || !budget.has("currency")
        || !budget.get("amount").isJsonPrimitive() || !budget.getAsJsonPrimitive("amount").isNumber()
        || budget.get("amount").getAsDouble() < 0
        || !budget.get("currency").isJsonPrimitive() || !budget.getAsJsonPrimitive("currency").isString()
        || budget.get("currency").getAsString().length() != 3) {
      return false;
    }
    return true;
  }
}
