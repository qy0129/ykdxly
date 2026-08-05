package com.changlu.planner.agent.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 主 Agent 的循环决策器：每轮选择继续执行某个执行器，或判定任务完成。 */
public final class AgentRouter {
  public enum Action { EXECUTE, COMPLETE }

  public record Decision(Action action, String executorType, String executorName, String reason, String reply) {
    public static Decision execute(String executorType, String executorName, String reason) {
      return new Decision(Action.EXECUTE, executorType, executorName, reason, null);
    }

    public static Decision complete(String reply, String reason) {
      return new Decision(Action.COMPLETE, null, null, reason, reply);
    }
  }

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

  public Decision route(String request, boolean hasDocuments,
                        com.changlu.planner.agent.core.tool.ToolRegistry tools,
                        com.changlu.planner.agent.core.registry.SubagentRegistry subagents)
      throws Exception {
    return route(request, hasDocuments, tools, subagents, new com.changlu.planner.agent.core.contract.AgentLoopState());
  }

  public Decision route(String request, boolean hasDocuments,
                        com.changlu.planner.agent.core.tool.ToolRegistry tools,
                        com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                        com.changlu.planner.agent.core.contract.AgentLoopState state) throws Exception {
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的主 Agent，在一个循环中持续调度执行器，直到用户请求被完整满足。
        每轮你输出一个决策：
        - 继续执行：{"action":"execute","executorType":"tool或subagent","executorName":"名称","reason":"简短原因"}
        - 任务完成：{"action":"complete","reply":"最终回复","reason":"简短原因"}
        只有确认用户请求已被完整满足时才输出 complete，否则继续选择下一个执行器。
        不要重复选择已完成的步骤；如果剩余工作只是汇总或已无必要动作，直接输出 complete。
        planning.assistant 负责普通对话、查询计划数据，以及创建或调整计划、阶段、任务、待办、日程。
        review 负责生成基于真实执行记录的每日复盘。
        briefing 负责生成包含今日计划、日程、待办、天气和相关新闻的简报。
        research 负责搜索公开网页并提供来源链接。
        scheduling 负责检查日程冲突、可用时段和安排时长，不负责修改日程。
        document 负责分析上传文件、总结文件和基于文件内容问答；根据文件创建计划或任务仍交给 planning.assistant。
        memory 负责回答"你记得我什么"、记住或忘记长期偏好；普通对话中的记忆提取由运行时自动完成。
        learning 负责学习目标管理、学习进度分析、学习计划建议和知识领域梳理；创建学习目标时先生成草案。
        专业领域能力只能从 Subagent Registry 的元数据中选择，不得发明未注册的执行器。
        结合 description、supportedScenarios 和 unsupportedScenarios 判断职责边界。
        当前请求是否附带文件：%s。
        已完成的步骤（用于判断是否还有剩余工作，空则输出"无"）：%s
        只输出 JSON。
        可用 Tools：%s
        可用 Subagents：%s
        """.formatted(hasDocuments,
            state.steps.isEmpty() ? "无" : gson.toJson(state.steps),
            gson.toJson(tools.definitions()), gson.toJson(subagents.definitions()))));
    messages.add(ModelClient.message("user", request));
    try {
      JsonObject result = model.choose(messages);
      if ("complete".equals(string(result, "action", ""))) {
        return Decision.complete(string(result, "reply", "已完成。"), string(result, "reason", ""));
      }
      String type = string(result, "executorType", "tool");
      String name = string(result, "executorName", "planning.assistant");
      // 文生图必须先经过 Subagent 解析自然语言，不能让模型直接调用底层工具。
      if ("tool".equals(type) && "image.generate".equals(name)
          && subagents.contains("image.generation")) {
        type = "subagent";
        name = "image.generation";
      }
      if ("subagent".equals(type)) subagents.require(name); else tools.require(name);
      return avoidRepeatedCompletedExecutor(
          Decision.execute(type, name, string(result, "reason", "")), state);
    } catch (Exception error) {
      return avoidRepeatedCompletedExecutor(fallback(request, hasDocuments, subagents, state, error), state);
    }
  }

  /** 模型可能在完成后重复选择同一个执行器，避免查询或写操作被无条件重复执行。 */
  private Decision avoidRepeatedCompletedExecutor(Decision decision,
                                                  com.changlu.planner.agent.core.contract.AgentLoopState state) {
    if (decision.action() != Action.EXECUTE || state == null || state.steps.isEmpty()) return decision;
    JsonElement lastElement = state.steps.get(state.steps.size() - 1);
    if (!lastElement.isJsonObject()) return decision;
    JsonObject last = lastElement.getAsJsonObject();
    if (!"COMPLETED".equals(string(last, "status", ""))) return decision;
    if (!decision.executorType().equals(string(last, "executorType", ""))
        || !decision.executorName().equals(string(last, "executorName", ""))) return decision;
    return Decision.complete(string(last, "message", "任务已完成。"),
        "上一轮已完成相同执行器，停止重复执行");
  }

  private Decision fallback(String request, boolean hasDocuments,
                            com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                            com.changlu.planner.agent.core.contract.AgentLoopState state,
                            Exception error) {
    String normalized = request.replaceAll("\\s", "");
    if (hasDocuments && planningIntent(normalized)) {
      return Decision.execute("tool", "planning.assistant", "请求基于附件创建或调整计划数据");
    }
    if (hasDocuments) {
      return Decision.execute("subagent", "document", "请求附带文件，交给文件分析 Subagent");
    }
    if (normalized.contains("复盘") || normalized.contains("总结今天")) {
      return Decision.execute("subagent", "review", "模型路由失败，按明确复盘意图回退");
    }
    if (normalized.contains("简报") || normalized.contains("今日安排")) {
      return Decision.execute("subagent", "briefing", "模型路由失败，按明确简报意图回退");
    }
    if (normalized.contains("搜索") || normalized.contains("查资料") || normalized.contains("查新闻")) {
      return Decision.execute("subagent", "research", "模型路由失败，按明确搜索意图回退");
    }
    if (normalized.contains("冲突")
        || normalized.contains("检查日程")
        || normalized.contains("排期问题")) {
      return Decision.execute("subagent", "scheduling", "模型路由失败，按明确排期检查意图回退");
    }
    if (normalized.contains("文件") || normalized.contains("文档")
        || normalized.contains("附件") || normalized.contains("知识库")) {
      return Decision.execute("subagent", "document", "模型路由失败，按明确文件分析意图回退");
    }
    if (normalized.contains("你记得我") || normalized.contains("长期记忆")
        || normalized.contains("记住我的") || normalized.contains("忘记我的")) {
      return Decision.execute("subagent", "memory", "模型路由失败，按明确记忆意图回退");
    }
    if (normalized.contains("学习") || normalized.contains("课程")
        || normalized.contains("知识") || normalized.contains("掌握程度")
        || normalized.contains("学习计划") || normalized.contains("学习进度")) {
      return Decision.execute("subagent", "learning", "模型路由失败，按明确学习规划意图回退");
    }
    var match = subagents.bestMatch(hasDocuments ? request + " 附件" : request);
    if (match.isPresent()) return Decision.execute("subagent", match.get().definition().name(),
        "模型路由失败，按 Registry 场景元数据回退");
    // 模型路由不可用时，文生图请求仍必须进入图片 Subagent。
    if (subagents.contains("image.generation") && imageIntent(normalized)) {
      return Decision.execute("subagent", "image.generation", "按文生图意图回退到图片 Subagent");
    }
    if (!state.steps.isEmpty()) {
      return Decision.complete("已完成前面步骤，如需继续请再告诉我。",
          "模型路由失败，且已有已完成步骤，避免循环重复执行");
    }
    return Decision.execute("tool", "planning.assistant", "模型路由失败，回退核心规划能力：" + error.getMessage());
  }

  private boolean planningIntent(String request) {
    return request.contains("创建") || request.contains("安排") || request.contains("布置")
        || request.contains("生成任务") || request.contains("制定计划") || request.contains("加入待办")
        || request.contains("排进日程") || request.contains("调整计划");
  }

  private boolean imageIntent(String request) {
    return request.contains("\u6587\u751f\u56fe")
        || request.contains("\u751f\u6210\u56fe\u7247")
        || request.contains("\u753b\u4e00\u5f20")
        || request.contains("\u753b\u4e2a")
        || request.contains("\u5e2e\u6211\u753b")
        || request.contains("\u63d2\u753b")
        || request.contains("\u6d77\u62a5")
        || request.contains("\u58c1\u7eb8")
        || request.contains("\u5934\u50cf")
        || request.contains("ai\u7ed8\u56fe");
  }

  private String string(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }
}
