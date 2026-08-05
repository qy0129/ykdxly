package com.changlu.planner.agent.subagents.diet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * 确定性营养目标计算器（纯函数，无模型依赖）。
 *
 * <p>遵循"确定性服务负责业务事实，大模型只做表达"原则：能量与三大营养素目标可以精确复算并单测覆盖，
 * 模型只负责基于给定目标生成菜单文本。规则见设计 §5.3：BMR 用 Mifflin-St Jeor 公式，
 * TDEE 乘活动系数，目标调整减脂 −15% / 增肌 +10% / 保持与控糖 = TDEE，
 * 蛋白质按目标取 1.8 / 1.2 / 1.3 g/kg，脂肪占摄入热量 27%，碳水为剩余热量 ÷ 4，
 * 每日总能量低于 1200 kcal 时取 1200 并标记 DIET_ENERGY_FLOOR 风险。
 * 减脂目标下若提供 targetWeightKg，额外输出目标体重与减重缺口（weightLossTargetKg），
 * 缺口超过 10kg 时标记 DIET_WEIGHT_LOSS_TARGET_HIGH 风险；能量目标仍按 −15% 保守计算。
 */
public final class DietTargetCalculator {
  private DietTargetCalculator() {}

  /** 每日营养目标计算结果：dailyTargets 为结构化目标，riskCodes 为额外风险码（如能量下限）。 */
  public record TargetCalculation(JsonObject dailyTargets, List<String> riskCodes) {
    public TargetCalculation {
      riskCodes = riskCodes == null ? List.of() : List.copyOf(riskCodes);
    }
  }

  private enum Goal { LOSS, GAIN, MAINTAIN, SUGAR }

  public static final double MIN_ENERGY_KCAL = 1200.0;
  public static final String METHOD = "mifflin-st-jeor";

  /**
   * 计算每日能量与三大营养素目标。任一必需字段（goal / age / sex / heightCm / weightKg / activityLevel）
   * 缺失时抛出异常——调用方（DietSubagent）会先经 {@code DietPolicy.requiredFields} 判定并返回 WAITING_USER。
   */
  public static TargetCalculation calculate(DietRequest request) {
    Goal goal = classify(request.goal());
    Double age = request.profileNumber("age");
    String sex = request.profileText("sex");
    Double heightCm = request.profileNumber("heightCm");
    Double weightKg = request.profileNumber("weightKg");
    String activity = request.profileText("activityLevel");
    if (request.goal().isBlank() || age == null || sex.isBlank()
        || heightCm == null || weightKg == null || activity.isBlank()) {
      throw new IllegalArgumentException("DIET_REQUIRED_FIELDS_MISSING");
    }
    if (age < 18 || age > 120) throw new IllegalArgumentException("DIET_AGE_INVALID");
    if (heightCm < 100 || heightCm > 250) throw new IllegalArgumentException("DIET_HEIGHT_INVALID");
    if (weightKg < 30 || weightKg > 300) throw new IllegalArgumentException("DIET_WEIGHT_INVALID");

    double bmr = mifflinStJeor(weightKg, heightCm, age, sex);
    double tdee = bmr * activityFactor(activity);
    double adjusted = switch (goal) {
      case LOSS -> tdee * 0.85;
      case GAIN -> tdee * 1.10;
      default -> tdee;
    };

    JsonArray riskCodes = new JsonArray();
    double energy = adjusted;
    if (energy < MIN_ENERGY_KCAL) {
      energy = MIN_ENERGY_KCAL;
      riskCodes.add("DIET_ENERGY_FLOOR");
    }

    double proteinPerKg = switch (goal) {
      case LOSS, GAIN -> 1.8;
      case SUGAR -> 1.3;
      default -> 1.2;
    };
    double proteinG = proteinPerKg * weightKg;
    double fatKcal = energy * 0.27;
    double fatG = fatKcal / 9.0;
    double carbsG = (energy - fatKcal - proteinG * 4.0) / 4.0;

    JsonObject targets = new JsonObject();
    targets.addProperty("energyKcal", Math.round(energy));
    targets.addProperty("proteinG", Math.round(proteinG));
    targets.addProperty("carbsG", Math.round(carbsG));
    targets.addProperty("fatG", Math.round(fatG));
    targets.addProperty("estimated", true);
    targets.addProperty("method", METHOD);
    if (riskCodes.size() > 0) targets.addProperty("energyFloorApplied", true);

    // 减脂目标下，用户提供目标体重时输出确定性减重缺口，并在缺口过大时附加分阶段风险提示。
    // 能量目标保持 −15% 保守缺口不变，绝不因目标激进而降低能量（设计 §5.3 安全优先）。
    Double targetWeightKg = request.profileNumber("targetWeightKg");
    if (goal == Goal.LOSS && targetWeightKg != null && targetWeightKg < weightKg) {
      double weightLossKg = Math.round((weightKg - targetWeightKg) * 10.0) / 10.0;
      targets.addProperty("targetWeightKg", targetWeightKg);
      targets.addProperty("weightLossTargetKg", weightLossKg);
      if (weightLossKg > 10.0) riskCodes.add("DIET_WEIGHT_LOSS_TARGET_HIGH");
    }
    return new TargetCalculation(targets, riskCodesAsList(riskCodes));
  }

  private static List<String> riskCodesAsList(JsonArray codes) {
    java.util.ArrayList<String> list = new java.util.ArrayList<>();
    codes.forEach(element -> list.add(element.getAsString()));
    return List.copyOf(list);
  }

  /** Mifflin-St Jeor：男 10w+6.25h−5a+5；女 10w+6.25h−5a−161。 */
  private static double mifflinStJeor(double weightKg, double heightCm, double age, String sex) {
    double base = 10 * weightKg + 6.25 * heightCm - 5 * age;
    return "male".equals(sex) ? base + 5 : base - 161;
  }

  private static double activityFactor(String level) {
    return switch (level) {
      case "sedentary" -> 1.2;
      case "light" -> 1.375;
      case "active" -> 1.725;
      case "very_active" -> 1.9;
      default -> 1.55; // moderate
    };
  }

  /** 把用户的目标表述归类为确定性枚举；未识别目标按保持处理（不改变 TDEE）。 */
  private static Goal classify(String goal) {
    String normalized = goal == null ? "" : goal.replaceAll("\\s", "");
    if (normalized.contains("减脂") || normalized.contains("减肥") || normalized.contains("减重")
        || normalized.contains("瘦身") || normalized.contains("减脂餐")) return Goal.LOSS;
    if (normalized.contains("增肌") || normalized.contains("健身") || normalized.contains("增重")
        || normalized.contains("增肌餐")) return Goal.GAIN;
    if (normalized.contains("控糖") || normalized.contains("血糖")) return Goal.SUGAR;
    return Goal.MAINTAIN; // 保持健康 / 均衡营养 / 维持 / 其他
  }
}
