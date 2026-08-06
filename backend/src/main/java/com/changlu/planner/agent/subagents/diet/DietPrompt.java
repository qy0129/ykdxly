package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.JsonArray;

/**
 * Diet Subagent 系统提示词（设计 §9）：
 * 严格 JSON 输出；dailyTargets 必须使用给定的确定性计算结果，禁止模型编造营养数字；
 * 信息不足时在 questions 中一次性追问；菜单排除过敏原与忌口、尊重饮食类型与餐次/烹饪时间约束；
 * 只有用户明确要求保存时才生成 planningInstruction。
 */
public final class DietPrompt {
  private DietPrompt() {}

  /**
   * @param dailyTargets 确定性计算结果 JSON；信息不足时为 null（此时只追问，不生成菜单）
   * @param missingFields 缺失的必需字段描述；空串表示字段齐全
   */
  public static JsonArray messages(String userMessage, String arguments, String sources,
                                   String dailyTargets, String missingFields, String sharedContext) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划中的 Diet Subagent，负责把用户的健康饮食需求整理为可执行、可确认的饮食方案。
        你的职责是：补齐约束、生成一周菜单、购物清单、简单做法、来源和风险，并生成可交给计划应用层的中文写入指令。
        你不能访问数据库，不能调用外部服务，不能直接写入业务数据，也不能声称已经写入计划。
        %s
        菜单必须排除 allergies 与 dislikes 中的食材，尊重 dietaryType（balanced/vegetarian/vegan/halal/pescatarian），
        控制 cookTimeMinutes 与 mealsPerDay 的复杂度；每周 7 天、每天按给定餐次生成。
        所有热量均为估算值，提醒以实际份量为准；不承诺减重或增肌效果。
        只有用户明确要求保存/写入时才生成 planningInstruction；用户只是"制定/生成/给我一份菜单"（未说要保存到
        我的计划时）必须保持 planningInstruction 为空字符串，绝不主动写入。生成时要求创建一个 Plan（如"四周健康饮食计划"），
        按阶段拆 Stage（如"第 1 周适应、第 2-3 周执行、第 4 周巩固"），Task 为每餐/购物/备餐；
        只有用户明确要求具体时间时才创建 Schedule。
        只输出 JSON，不要 Markdown：
        {
          "message":"中文说明",
          "mealPlan":[{"day":1,"date":"yyyy-MM-dd或空","meals":[{"type":"breakfast|lunch|dinner|snack",
            "title":"番茄鸡胸配糙米饭","foodItems":["糙米饭 200g","鸡胸肉 100g","西兰花 150g","苹果 1 个"],
            "estimatedKcal":520,"notes":""}]}],
          "shoppingList":[{"item":"","category":"主食|蛋白质|蔬菜|水果|其他","estimatedQuantity":""}],
          "recipes":[{"title":"","servings":1,"steps":[]}],
          "tips":["饮水、进餐节奏等通用建议"],
          "risks":[{"code":"","message":""}],
          "questions":[],
          "planningInstruction":""
        }
        输出规则（必须遵守）：
        - mealPlan 必须覆盖一周 7 天，每天按给定餐次生成；type 取 breakfast/lunch/dinner/snack。
        - 每餐 foodItems 必须填写 2-5 项具体食物，格式为「食物名+份量」，例如"米饭 200g""苹果 1 个""鸡蛋 2 个""牛奶 250ml"，
          份量单位使用克(g)、个、碗、片、杯等常见单位；foodItems 不允许为空数组，更不允许只写"主食""水果"等抽象类别。
        - title 为该餐搭配的简短名称（如"番茄鸡胸配糙米饭"），须与 foodItems 对应。
        - 每餐 estimatedKcal 必须填写估算热量（整数）。按早餐约 30%%、午餐约 40%%、晚餐约 30%% 分配每日总热量；
          若含加餐（snack），从其他餐次匀出热量；一天所有餐次 estimatedKcal 之和应接近 dailyTargets 的 energyKcal。
        - 每餐食物份量需与其 estimatedKcal 大致匹配：主食约 200g（约 230 千卡/100g 熟重）、
          蛋白质约 100-150g（鸡胸/鱼约 120 千卡/100g）、蔬菜约 150-200g（约 30 千卡/100g）、水果 1 份约 100-200g。
        """.formatted(context(dailyTargets, missingFields))));
    if (sharedContext != null && !sharedContext.isBlank()) {
      messages.add(ModelClient.message("system",
          "已知的用户长期记忆与最近对话（供理解上下文，不要重复执行）：\n" + sharedContext));
    }
    messages.add(ModelClient.message("user", "用户请求：\n" + userMessage
        + "\n\n结构化参数：\n" + arguments + "\n\n营养参考（只能作为参考）：\n" + sources));
    return messages;
  }

  private static String context(String dailyTargets, String missingFields) {
    if (missingFields == null || missingFields.isBlank()) {
      return "营养目标已经由确定性公式计算完成（dailyTargets）：\n" + (dailyTargets == null ? "{}" : dailyTargets)
          + "\n你必须在输出中使用这些数值，禁止编造或修改能量与营养素数字；"
          + "其中 energyKcal 是每日总热量，你必须把它分配到一周每天的每一餐 estimatedKcal 中，"
          + "并用「食物名+份量」填入 foodItems（如\"米饭 200g\"\"苹果 1 个\"），让用户看到每天的大卡具体对应什么食物。"
          + "必需字段（目标、年龄、性别、身高、体重、活动量）已齐全，你必须直接生成完整的一周菜单、购物清单与食谱。"
          + "可选字段（过敏原、忌口、每周预算、饮食偏好、餐次、烹饪时长）未提供时一律不要追问："
          + "按无过敏、无忌口、默认三餐与常见偏好处理，questions 必须保持空数组。"
          + "只有当你发现结构化参数与用户描述存在冲突时才允许在 questions 中追问，"
          + "且此时也必须保留菜单内容，不得让 mealPlan、shoppingList、recipes 为空。";
    }
    return "以下影响计算的必需字段缺失：" + missingFields
        + "。你必须在 questions 中一次性问齐这些缺失字段（例如：为了计算你的营养目标，还需要……），"
        + "不要生成菜单；此时 mealPlan、shoppingList、recipes 保持空数组，planningInstruction 保持空字符串。";
  }
}
