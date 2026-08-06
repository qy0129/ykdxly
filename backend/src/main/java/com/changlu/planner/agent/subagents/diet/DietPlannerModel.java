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
}
