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
        review 负责生成基于真实执行记录的每日复盘。
        briefing 负责生成包含今日计划、日程、待办、天气和相关新闻的简报。
        research 负责搜索公开网页并提供来源链接。
        scheduling 负责检查日程冲突、可用时段和安排时长，不负责修改日程。
        document 负责分析上传文件、总结文件和基于文件内容问答；根据文件创建计划或任务仍交给 planning.assistant。
        memory 负责回答”你记得我什么”、记住或忘记长期偏好；普通对话中的记忆提取由运行时自动完成。
        learning 负责学习目标管理、学习进度分析、学习计划建议和知识领域梳理；创建学习目标时先生成草案。
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
    if (hasDocuments) {
      return new Decision("subagent", "document", "请求附带文件，交给文件分析 Subagent");
    }
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
    if (normalized.contains("文件") || normalized.contains("文档")
        || normalized.contains("附件") || normalized.contains("知识库")) {
      return new Decision("subagent", "document", "模型路由失败，按明确文件分析意图回退");
    }
    if (normalized.contains("你记得我") || normalized.contains("长期记忆")
        || normalized.contains("记住我的") || normalized.contains("忘记我的")) {
      return new Decision("subagent", "memory", "模型路由失败，按明确记忆意图回退");
    }
    if (normalized.contains("学习") || normalized.contains("课程")
        || normalized.contains("知识") || normalized.contains("掌握程度")
        || normalized.contains("学习计划") || normalized.contains("学习进度")) {
      return new Decision("subagent", "learning", "模型路由失败，按明确学习规划意图回退");
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
