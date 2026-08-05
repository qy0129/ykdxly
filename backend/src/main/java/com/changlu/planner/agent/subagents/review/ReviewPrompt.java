package com.changlu.planner.agent.subagents.review;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 复盘 Subagent 的模型输入。 */
public final class ReviewPrompt {
  private ReviewPrompt() {}

  public static JsonArray messages(JsonObject facts) {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的复盘 Subagent。输入 facts 由后端从数据库实时生成，只能基于这些真实事实生成日报，不能虚构，也不能修改任何数据。
        logs 是今天的执行记录，recentExecution 是最近 7 天的执行记录；completedTasks、completed、scheduleCompleted、delayed、blocked、focusMinutes 和 estimationError7d 是后端统计值。
        总结必须引用输入中的实际数字、事项或原因；没有对应事实时要明确说“没有记录”，不能套用固定的鼓励文案或假设用户完成了某件事。
        只输出 JSON：
        {"summary":"2至4段简洁中文总结","highlights":["最多3项"],"risks":["最多3项"],"nextActions":["明天可执行的最多3项行动"]}
        没有执行记录时如实说明；nextActions 只能根据事实给出可执行建议，不要编造不存在的任务名称。
        """));
    messages.add(ModelClient.message("user", facts.toString()));
    return messages;
  }
}
