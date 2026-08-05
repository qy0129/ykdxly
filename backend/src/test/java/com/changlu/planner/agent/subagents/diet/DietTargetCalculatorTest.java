package com.changlu.planner.agent.subagents.diet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.changlu.planner.agent.subagents.diet.DietTargetCalculator.TargetCalculation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** 确定性复算（设计 §5.3 / §11）：BMR、TDEE、目标调整、三大营养素、性别差异、活动系数与能量下限。 */
final class DietTargetCalculatorTest {
  @Test void maleLossGoalUsesMifflinStJeorWith15PercentDeficit() {
    // BMR = 10*70 + 6.25*175 - 5*30 + 5 = 1648.75；TDEE = 1648.75*1.55 = 2555.5625
    // 减脂 -15% = 2172.228125 → 2172；蛋白质 1.8*70 = 126；脂肪 27% / 9 = 65；碳水剩余 / 4 = 270
    TargetCalculation result = DietTargetCalculator.calculate(
        request("减脂", 30, "male", 175, 70, "moderate"));
    JsonObject targets = result.dailyTargets();
    assertEquals(2172, targets.get("energyKcal").getAsInt());
    assertEquals(126, targets.get("proteinG").getAsInt());
    assertEquals(65, targets.get("fatG").getAsInt());
    assertEquals(270, targets.get("carbsG").getAsInt());
    assertTrue(targets.get("estimated").getAsBoolean());
    assertEquals("mifflin-st-jeor", targets.get("method").getAsString());
    assertTrue(result.riskCodes().isEmpty());
  }

  @Test void femaleGainGoalUses10PercentSurplus() {
    // 女性 BMR = 10*60 + 6.25*165 - 5*28 - 161 = 1330.25；TDEE = 1330.25*1.375 = 1829.09375
    // 增肌 +10% = 2012.003125 → 2012；蛋白质 1.8*60 = 108；脂肪 60；碳水 259
    TargetCalculation result = DietTargetCalculator.calculate(
        request("增肌", 28, "female", 165, 60, "light"));
    JsonObject targets = result.dailyTargets();
    assertEquals(2012, targets.get("energyKcal").getAsInt());
    assertEquals(108, targets.get("proteinG").getAsInt());
    assertEquals(60, targets.get("fatG").getAsInt());
    assertEquals(259, targets.get("carbsG").getAsInt());
  }

  @Test void maintainGoalUsesTdeeWith12gProteinPerKg() {
    // 保持：TDEE 2555.5625 → 2556；蛋白质 1.2*70 = 84；脂肪 77；碳水 382
    TargetCalculation result = DietTargetCalculator.calculate(
        request("保持健康", 30, "male", 175, 70, "moderate"));
    JsonObject targets = result.dailyTargets();
    assertEquals(2556, targets.get("energyKcal").getAsInt());
    assertEquals(84, targets.get("proteinG").getAsInt());
    assertEquals(77, targets.get("fatG").getAsInt());
    assertEquals(382, targets.get("carbsG").getAsInt());
  }

  @Test void sugarGoalKeepsTdeeWith13gProteinPerKg() {
    // 控糖：TDEE 2555.5625 → 2556；蛋白质 1.3*70 = 91；脂肪 77；碳水 375
    TargetCalculation result = DietTargetCalculator.calculate(
        request("控糖饮食", 30, "male", 175, 70, "moderate"));
    JsonObject targets = result.dailyTargets();
    assertEquals(2556, targets.get("energyKcal").getAsInt());
    assertEquals(91, targets.get("proteinG").getAsInt());
    assertEquals(375, targets.get("carbsG").getAsInt());
  }

  @Test void activityFactorScalesTdee() {
    // 同一人 sedentary 1978.5 → 1979；very_active 3132.625 → 3133
    assertEquals(1979, DietTargetCalculator.calculate(
        request("保持健康", 30, "male", 175, 70, "sedentary")).dailyTargets().get("energyKcal").getAsInt());
    assertEquals(3133, DietTargetCalculator.calculate(
        request("保持健康", 30, "male", 175, 70, "very_active")).dailyTargets().get("energyKcal").getAsInt());
  }

  @Test void energyFloorAppliedBelow1200Kcal() {
    // 女性 55/150/70 保持：BMR = 976.5，TDEE = 1171.8 < 1200 → 取 1200 + DIET_ENERGY_FLOOR
    // 蛋白质 1.2*55 = 66；脂肪 (1200*0.27)/9 = 36；碳水 (1200-324-264)/4 = 153
    TargetCalculation result = DietTargetCalculator.calculate(
        request("保持健康", 70, "female", 150, 55, "sedentary"));
    JsonObject targets = result.dailyTargets();
    assertEquals(1200, targets.get("energyKcal").getAsInt());
    assertTrue(targets.get("energyFloorApplied").getAsBoolean());
    assertEquals(66, targets.get("proteinG").getAsInt());
    assertEquals(36, targets.get("fatG").getAsInt());
    assertEquals(153, targets.get("carbsG").getAsInt());
    assertTrue(result.riskCodes().contains("DIET_ENERGY_FLOOR"));
  }

  @Test void lossGoalWithTargetWeightAddsDeficitFieldsAndHighRisk() {
    // 减脂目标 + 目标体重：dailyTargets 增加 targetWeightKg 与 weightLossTargetKg，缺口 12kg >10 触发 HIGH 风险
    JsonObject profile = profile(30, "male", 175, 70, "moderate");
    profile.addProperty("targetWeightKg", 58);
    TargetCalculation result = DietTargetCalculator.calculate(
        new DietRequest("减脂", profile, "none", new JsonArray(), new JsonArray(), new JsonArray(),
            3, 30, new JsonObject(), new JsonArray(), false));
    JsonObject targets = result.dailyTargets();
    // 能量仍按 −15% 保守：2172，与 maleLossGoalUsesMifflinStJeorWith15PercentDeficit 一致
    assertEquals(2172, targets.get("energyKcal").getAsInt());
    assertEquals(58.0, targets.get("targetWeightKg").getAsDouble());
    assertEquals(12.0, targets.get("weightLossTargetKg").getAsDouble());
    assertTrue(result.riskCodes().contains("DIET_WEIGHT_LOSS_TARGET_HIGH"));
  }

  @Test void lossGoalWithSmallTargetOmitsHighRisk() {
    // 缺口 5kg ≤ 10：不触发 HIGH 风险，但仍输出目标与缺口字段
    JsonObject profile = profile(30, "male", 175, 70, "moderate");
    profile.addProperty("targetWeightKg", 65);
    TargetCalculation result = DietTargetCalculator.calculate(
        new DietRequest("减脂", profile, "none", new JsonArray(), new JsonArray(), new JsonArray(),
            3, 30, new JsonObject(), new JsonArray(), false));
    JsonObject targets = result.dailyTargets();
    assertEquals(65.0, targets.get("targetWeightKg").getAsDouble());
    assertEquals(5.0, targets.get("weightLossTargetKg").getAsDouble());
    assertFalse(result.riskCodes().contains("DIET_WEIGHT_LOSS_TARGET_HIGH"));
  }

  @Test void nonLossGoalIgnoresTargetWeightFields() {
    // 保持目标 + 目标体重：targetWeightKg / weightLossTargetKg 不输出（目标体重仅用于减脂缺口提示）
    JsonObject profile = profile(30, "male", 175, 70, "moderate");
    profile.addProperty("targetWeightKg", 65);
    TargetCalculation result = DietTargetCalculator.calculate(
        new DietRequest("保持健康", profile, "none", new JsonArray(), new JsonArray(), new JsonArray(),
            3, 30, new JsonObject(), new JsonArray(), false));
    JsonObject targets = result.dailyTargets();
    assertFalse(targets.has("targetWeightKg"));
    assertFalse(targets.has("weightLossTargetKg"));
    assertFalse(result.riskCodes().contains("DIET_WEIGHT_LOSS_TARGET_HIGH"));
  }

  @Test void missingRequiredFieldRefusesCalculation() {
    // 缺少 goal：不计算（由 DietPolicy.requiredFields 负责追问）
    JsonObject profile = new JsonObject();
    profile.addProperty("age", 30); profile.addProperty("sex", "male");
    profile.addProperty("heightCm", 175); profile.addProperty("weightKg", 70);
    profile.addProperty("activityLevel", "moderate");
    DietRequest incomplete = new DietRequest("", profile, "none", new JsonArray(), new JsonArray(),
        new JsonArray(), 3, 30, new JsonObject(), new JsonArray(), false);
    assertThrows(IllegalArgumentException.class, () -> DietTargetCalculator.calculate(incomplete));
  }

  private DietRequest request(String goal, int age, String sex, double height, double weight,
                              String activity) {
    return new DietRequest(goal, profile(age, sex, height, weight, activity), "none",
        new JsonArray(), new JsonArray(), new JsonArray(), 3, 30, new JsonObject(), new JsonArray(), false);
  }

  private JsonObject profile(int age, String sex, double height, double weight, String activity) {
    JsonObject profile = new JsonObject();
    profile.addProperty("age", age); profile.addProperty("sex", sex);
    profile.addProperty("heightCm", height); profile.addProperty("weightKg", weight);
    profile.addProperty("activityLevel", activity);
    return profile;
  }
}
