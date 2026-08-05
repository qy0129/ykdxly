package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 基于模型的 Diet 参数提取实现（设计补充：Web 入口只传 message，结构参数由模型从自然语言提取）。
 *
 * <p>系统提示要求模型严格输出符合 input.schema.json 的 JSON，只填消息中明确提到的字段，
 * 未提到的省略（与 DietPolicy.requiredFields 的分级追问互补：提取成功的字段直接进入计算，
 * 仍缺失的字段照常一次性追问）。提取失败或模型输出非法时返回空对象，由调用方合并后回退原流程。
 */
public final class ModelDietArgumentExtractor implements DietArgumentExtractor {
  private final ModelClient model;

  public ModelDietArgumentExtractor(ModelClient model) {
    this.model = model;
  }

  @Override
  public JsonObject extract(String message) throws Exception {
    if (message == null || message.isBlank()) return new JsonObject();
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", SYSTEM_PROMPT));
    messages.add(ModelClient.message("user", message));
    JsonObject parsed = model.completeJson("diet-argument-extract", messages, 0.0, 600, 45, 2);
    return parsed == null ? new JsonObject() : parsed;
  }

  private static final String SYSTEM_PROMPT = """
      你是"长路计划"App 的饮食规划参数提取器。用户会发来一句自然语言的健康饮食需求，
      请提取其中明确提到的结构化参数，严格输出一个 JSON 对象，不要输出任何解释文字或 Markdown。

      输出字段（只输出用户明确提到的，未提到的字段一律省略，绝不猜测或编造）：
      - goal: 目标，字符串。减脂/减肥/减重/瘦身 → "减脂"；增肌/健身 → "增肌"；控糖/血糖 → "控糖"；保持健康/均衡营养 → "保持健康"
      - profile: 对象，包含
        - age: 年龄（数字）
        - sex: 性别，"male" 或 "female"
        - heightCm: 身高（厘米，数字）
        - weightKg: 当前体重（千克，数字）
        - targetWeightKg: 目标体重（千克，数字，如"减脂到58kg"→58）
        - activityLevel: 活动量，"sedentary"/"light"/"moderate"/"active"/"very_active"
          （久坐→sedentary；轻度运动/每周1-2次→light；每周3-5次→moderate；
          高强度/每天训练→active 或 very_active；未提及则省略）
      - dietaryType: 饮食类型，"balanced"/"vegetarian"/"vegan"/"halal"/"pescatarian"（素食→vegetarian，纯素→vegan，清真→halal，未提及则省略）
      - allergies: 过敏原，字符串数组（如"对花生过敏"→["花生"]）
      - dislikes: 忌口/不吃的食物，字符串数组
      - medicalConditions: 已知疾病或健康问题，字符串数组
      - mealsPerDay: 每日餐次，整数（如"一天三餐"→3）
      - cookTimeMinutes: 可接受的单餐烹饪时长（分钟，整数，如"做饭别超过30分钟"→30）
      - saveToPlanner: 用户是否明确要求把方案保存/写入计划，布尔值；未提及则省略

      示例：
      输入：减脂，女，162cm，70kg，目标减脂到58kg，平时久坐，不吃香菜
      输出：{"goal":"减脂","profile":{"sex":"female","heightCm":162,"weightKg":70,"targetWeightKg":58,"activityLevel":"sedentary"},"dislikes":["香菜"]}
      """;
}
