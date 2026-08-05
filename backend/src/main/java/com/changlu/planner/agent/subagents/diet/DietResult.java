package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.subagents.diet.DietTargetCalculator.TargetCalculation;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 输出领域记录（设计 §5.2）。dailyTargets 来自确定性计算器，绝不取自模型；
 * risks 至少包含三条强制声明（设计 §2.3），并叠加医学风险、能量下限风险与模型风险。
 */
public record DietResult(
    String message,
    DietRequest request,
    JsonObject dailyTargets,
    JsonArray mealPlan,
    JsonArray shoppingList,
    JsonArray recipes,
    JsonArray tips,
    JsonArray sources,
    JsonArray risks,
    JsonArray questions,
    String planningInstruction
) {
  public static DietResult fromGenerated(JsonObject generated, DietRequest request, JsonArray sources,
                                         TargetCalculation targets, JsonArray medicalRisks) {
    JsonObject daily = targets == null ? new JsonObject() : targets.dailyTargets().deepCopy();
    JsonArray risks = mandatoryRisks();
    if (medicalRisks != null) medicalRisks.forEach(risks::add);
    if (targets != null) {
      for (String code : targets.riskCodes()) {
        if ("DIET_ENERGY_FLOOR".equals(code)) {
          risks.add(risk(code, "每日能量目标已按安全下限调整至 1200 千卡，不建议低于此水平。"));
        } else if ("DIET_WEIGHT_LOSS_TARGET_HIGH".equals(code)) {
          risks.add(risk(code, "减重目标超过 10kg，建议分阶段进行：先按当前方案执行 1-2 个月观察身体反馈，"
              + "体重稳定后再调整目标，避免因目标过激影响健康。"));
        }
      }
    }
    array(generated, "risks").forEach(risks::add);
    return new DietResult(text(generated, "message", "已整理健康饮食方案。"), request, daily,
        array(generated, "mealPlan"), array(generated, "shoppingList"), array(generated, "recipes"),
        array(generated, "tips"), sources.deepCopy(), risks, array(generated, "questions"),
        text(generated, "planningInstruction", ""));
  }

  public JsonObject toData() {
    JsonObject value = new JsonObject();
    value.add("request", request.toJson());
    value.add("dailyTargets", dailyTargets.deepCopy());
    value.add("mealPlan", mealPlan.deepCopy());
    value.add("shoppingList", shoppingList.deepCopy());
    value.add("recipes", recipes.deepCopy());
    value.add("tips", tips.deepCopy());
    value.add("sources", sources.deepCopy());
    value.add("risks", risks.deepCopy());
    value.add("questions", questions.deepCopy());
    value.addProperty("planningInstruction", planningInstruction);
    return value;
  }

  /** 设计 §2.3 强制健康声明：所有输出必须包含估算声明、就医提示与不承诺效果。 */
  public static JsonArray mandatoryRisks() {
    JsonArray risks = new JsonArray();
    risks.add(risk("DIET_ESTIMATED_TARGETS", "能量与营养素目标为估算值，仅作参考，请以实际份量和身体反馈为准。"));
    risks.add(risk("DIET_MEDICAL_ADVICE", "慢性病、孕期、未成年人及服药人群，建议咨询医生或注册营养师。"));
    risks.add(risk("DIET_NO_EFFECT_GUARANTEE", "本方案不承诺减重或增肌效果。"));
    return risks;
  }

  public static JsonObject risk(String code, String message) {
    JsonObject risk = new JsonObject();
    risk.addProperty("code", code);
    risk.addProperty("message", message);
    return risk;
  }

  private static String text(JsonObject value, String name, String fallback) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : fallback;
  }
  private static JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name).deepCopy() : new JsonArray();
  }
}
