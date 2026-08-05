package com.changlu.planner.agent.subagents.diet;

import com.google.gson.JsonObject;

/**
 * 自然语言参数提取器（修复 Web 入口只传 message、不带结构化 arguments 的问题）。
 *
 * <p>把用户消息（如"减脂，女，162cm，70kg，目标减脂到58kg"）解析为与
 * {@link DietRequest#from(JsonObject)} 兼容的结构化 JsonObject，键名对齐
 * input.schema.json（goal / profile{age,sex,heightCm,weightKg,targetWeightKg,activityLevel} /
 * dietaryType / allergies / dislikes / medicalConditions / mealsPerDay / cookTimeMinutes /
 * saveToPlanner 等）。契约：只返回能从消息中确认的字段，未提及的省略，绝不编造。
 */
public interface DietArgumentExtractor {
  /** 从自然语言消息提取结构化参数；无法确认时返回空对象，调用方可安全合并。 */
  JsonObject extract(String message) throws Exception;
}
