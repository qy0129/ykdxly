package com.changlu.planner.agent.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 主 Agent 的轻量决策器：模型选择核心规划 Tool 或专业 Subagent。 */
public final class AgentRouter {
  public record Decision(String executorType, String executorName, String reason) {}

  private final ModelClient model;
  private final Gson gson = new Gson();

  public AgentRouter(ModelClient model) { this.model = model; }

  public Decision route(String request, ToolRegistry tools, SubagentRegistry subagents) throws Exception {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的主 Agent，只负责把用户请求分配给一个最合适的执行器。
        planning.assistant 负责普通对话、查询计划数据，以及创建或调整计划、阶段、任务、待办、日程。
        review 负责生成基于真实执行记录的每日复盘。
        briefing 负责生成包含今日计划、日程、待办、天气和相关新闻的简报。
        research 负责搜索公开网页并提供来源链接。
        scheduling 负责检查日程冲突、可用时段和安排时长，不负责修改日程。
        只输出 JSON：{"executorType":"tool或subagent","executorName":"名称","reason":"简短原因"}。
        可用 Tools：%s
        可用 Subagents：%s
        """.formatted(gson.toJson(tools.definitions()), gson.toJson(subagents.definitions()))));
    messages.add(ModelClient.message("user", request));
    try {
      JsonObject result = model.completeJson("agent-router", messages, 0, 300, 12, 1);
      String type = string(result, "executorType", "tool");
      String name = string(result, "executorName", "planning.assistant");
      if ("subagent".equals(type)) subagents.require(name); else tools.require(name);
      return new Decision(type, name, string(result, "reason", ""));
    } catch (Exception error) {
      return fallback(request, error);
    }
  }

  private Decision fallback(String request, Exception error) {
    String normalized = request.replaceAll("\\s", "");
    if (normalized.contains("复盘") || normalized.contains("总结今天")) {
      return new Decision("subagent", "review", "模型路由失败，按明确复盘意图回退");
    }
    if (normalized.contains("简报") || normalized.contains("今日安排")) {
      return new Decision("subagent", "briefing", "模型路由失败，按明确简报意图回退");
    }
    if (normalized.contains("搜索") || normalized.contains("查资料") || normalized.contains("查新闻")) {
      return new Decision("subagent", "research", "模型路由失败，按明确搜索意图回退");
    }
    if (normalized.contains("冲突")
        || normalized.contains("检查日程")
        || normalized.contains("排期问题")) {
      return new Decision("subagent", "scheduling", "模型路由失败，按明确排期检查意图回退");
    }
    return new Decision("tool", "planning.assistant", "模型路由失败，回退核心规划能力：" + error.getMessage());
  }

  private String string(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }
}
