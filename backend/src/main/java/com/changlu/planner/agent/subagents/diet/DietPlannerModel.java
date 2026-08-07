package com.changlu.planner.agent.subagents.diet;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * 菜单与文案生成模型接口。营养目标（dailyTargets）由确定性计算器给出，
 * 模型只负责组织语言、生成菜单、购物清单、做法与追问。
 */
@FunctionalInterface
public interface DietPlannerModel {
  /**
   * @param dailyTargets 确定性营养目标；必需字段缺失时为 null（模型只生成 questions）
   * @param missingFields 缺失的必需字段描述；空列表表示字段齐全
   * @param sharedContext 用户长期记忆与最近对话（供模型结合上下文理解，不重复执行）
   */
  JsonObject plan(DietRequest request, JsonArray sources, JsonObject dailyTargets,
                  List<String> missingFields, String sharedContext) throws Exception;

  /**
   * 带修改上下文的生成入口：userRequest 是用户最新要求（草案修改文本，如"把周二的晚餐换成鸡胸肉"），
   * previousMealPlan 是上一版菜单。修改既有方案时做局部调整：以用户最新要求为准，
   * 未提及的餐次/日期保持与上一版一致，而不是全量重生成导致修改丢失。
   * 默认实现退回无上下文版本，保证只实现 plan 的测试替身不用改。
   */
  default JsonObject planWithContext(DietRequest request, JsonArray sources, JsonObject dailyTargets,
                                     List<String> missingFields, String sharedContext,
                                     String userRequest, JsonArray previousMealPlan) throws Exception {
    return plan(request, sources, dailyTargets, missingFields, sharedContext);
  }
}
