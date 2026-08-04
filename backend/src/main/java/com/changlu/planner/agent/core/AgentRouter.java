package com.changlu.planner.agent.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/** 主 Agent 的轻量决策器：模型选择核心规划 Tool 或专业 Subagent。 */
public final class AgentRouter {
  public record Decision(String executorType, String executorName, String reason) {}

  @FunctionalInterface
  public interface RouteModel {
    JsonObject choose(JsonArray messages) throws Exception;
  }

  private final RouteModel model;
  private final Gson gson = new Gson();

  public AgentRouter(ModelClient model) {
    this(messages -> model.completeJson("agent-router", messages, 0, 300, 12, 1));
  }

  public AgentRouter(RouteModel model) { this.model = model; }

  public Decision route(String request, boolean hasDocuments, ToolRegistry tools,
                        com.changlu.planner.agent.core.registry.SubagentRegistry subagents)
      throws Exception {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的主 Agent，只负责把用户请求分配给一个最合适的执行器。
        planning.assistant 负责普通对话、查询计划数据，以及创建或调整计划、阶段、任务、待办、日程。
        专业领域能力只能从 Subagent Registry 的元数据中选择，不得发明未注册的执行器。
        结合 description、supportedScenarios 和 unsupportedScenarios 判断职责边界。
        当前请求是否附带文件：%s。
        只输出 JSON：{"executorType":"tool或subagent","executorName":"名称","reason":"简短原因"}。
        可用 Tools：%s
        可用 Subagents：%s
        """.formatted(hasDocuments, gson.toJson(tools.definitions()), gson.toJson(subagents.definitions()))));
    messages.add(ModelClient.message("user", request));
    try {
      JsonObject result = model.choose(messages);
      String type = string(result, "executorType", "tool");
      String name = string(result, "executorName", "planning.assistant");
      if ("subagent".equals(type)) subagents.require(name); else tools.require(name);
      return new Decision(type, name, string(result, "reason", ""));
    } catch (Exception error) {
      return fallback(request, hasDocuments, subagents, error);
    }
  }

  private Decision fallback(String request, boolean hasDocuments,
                            com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                            Exception error) {
    String normalized = request.replaceAll("\\s", "");
    if (hasDocuments && planningIntent(normalized)) {
      return new Decision("tool", "planning.assistant", "请求基于附件创建或调整计划数据");
    }
    var match = subagents.bestMatch(hasDocuments ? request + " 附件" : request);
    if (match.isPresent()) return new Decision("subagent", match.get().definition().name(),
        "模型路由失败，按 Registry 场景元数据回退");
    return new Decision("tool", "planning.assistant", "模型路由失败，回退核心规划能力：" + error.getMessage());
  }

  private boolean planningIntent(String request) {
    return request.contains("创建") || request.contains("安排") || request.contains("布置")
        || request.contains("生成任务") || request.contains("制定计划") || request.contains("加入待办")
        || request.contains("排进日程") || request.contains("调整计划");
  }

  private String string(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }
}
