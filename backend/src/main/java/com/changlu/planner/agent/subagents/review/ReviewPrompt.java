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
        你是长路计划的复盘 Subagent。只能基于给出的真实事实生成日报，不能虚构，也不能修改任何数据。
        只输出 JSON：
        {"summary":"2至4段简洁中文总结","highlights":["最多3项"],"risks":["最多3项"],"nextActions":["明天可执行的最多3项行动"]}
        没有执行记录时如实说明，并给出一项轻量的开始建议。
        """));
    messages.add(ModelClient.message("user", facts.toString()));
    return messages;
  }
}
