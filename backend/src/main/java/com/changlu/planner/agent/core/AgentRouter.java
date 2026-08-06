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
    return route(request, hasDocuments, tools, subagents, state, new JsonObject());
  }

  /**
   * 参数中可携带上一次旅行草案。用户取消草案后局部修改时，仍能稳定回到旅行子代理。
   */
  public Decision route(String request, boolean hasDocuments,
                        com.changlu.planner.agent.core.tool.ToolRegistry tools,
                        com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                        com.changlu.planner.agent.core.contract.AgentLoopState state,
                        JsonObject arguments) throws Exception {
    if (travelRevisionIntent(request, state, arguments, subagents)) {
      return Decision.execute("subagent", "travel", "当前旅行方案的局部修改");
    }
    // 大模型是主路由：每个请求都先交给模型选执行器。
    // 关键词规则不再抢占，只通过 correct() 在模型给出与明确意图相悖的决策时做最后纠正。
    JsonArray messages = new JsonArray();
    messages.add(ModelClient.message("system", """
        你是长路计划的主 Agent，在一个循环中持续调度执行器，直到用户请求被完整满足。
        每轮你输出一个决策：
        - 继续执行：{"action":"execute","executorType":"tool或subagent","executorName":"名称","reason":"简短原因"}
        - 任务完成：{"action":"complete","reply":"最终回复","reason":"简短原因"}
        只有确认用户请求已被完整满足时才输出 complete；只要还有明确的执行动作（创建/删除/修改/查询），就必须继续并选择执行器。不要重复选择已完成的步骤。

        【职责边界】
        - planning.assistant：普通对话、查询计划数据，以及创建、调整或删除计划、阶段、任务、待办、日程。
        - review：生成基于真实执行记录的每日复盘。
        - briefing：生成包含今日计划、日程、待办、天气和相关新闻的简报。
        - research：搜索公开网页并提供来源链接。
        - scheduling：检查日程冲突、可用时段和安排时长，不修改日程。
        - document：分析上传文件、总结文件和基于文件内容问答；根据文件创建计划或任务仍交给 planning.assistant。
        - memory：回答"你记得我什么"、记住或忘记长期偏好；普通对话中的记忆提取由运行时自动完成。
        - learning：学习目标管理、学习进度分析、学习计划建议和知识领域梳理；创建/修改/删除学习目标并生成待确认草案。
        - diet：健康饮食/减脂餐/增肌餐/一周食谱等饮食规划；用户明确要求"保存到我的计划"时才生成待确认草案，否则只给菜单。
        - travel：旅行/旅游行程规划（目的地、时间、预算、节奏、兴趣）。
        - image.generation：文生图（画一张、生成图片、海报、头像等）。

        【路由判例，务必遵守】
        - "创建学习目标：雅思7分"、"我想学高数达到95分"、"要学英语六级" → 路由 learning 创建学习目标。绝不能只输出 complete 或文字回复"我将生成草案/请确认"——必须派发 learning 真正生成待确认草案。
        - "删除雅思的长期计划"、"删除待办X"、"把任务改成Y" → 路由 planning.assistant（目标是计划/待办，不是学习目标）。
        - "删除学习目标"、"修改雅思目标" → 路由 learning（目标是学习目标）。
        - 只读查询（"看看我的计划"、"今天有什么任务"）→ planning.assistant 等相应执行器，actions 保持为空。
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
      Decision decision;
      if ("complete".equals(string(result, "action", ""))) {
        decision = Decision.complete(string(result, "reply", "已完成。"), string(result, "reason", ""));
      } else {
        String type = string(result, "executorType", "tool");
        String name = string(result, "executorName", "planning.assistant");
        // 文生图必须先经过 Subagent 解析自然语言，不能让模型直接调用底层工具。
        if ("tool".equals(type) && "image.generate".equals(name)
            && subagents.contains("image.generation")) {
          type = "subagent";
          name = "image.generation";
        }
        if ("subagent".equals(type)) subagents.require(name); else tools.require(name);
        decision = Decision.execute(type, name, string(result, "reason", ""));
      }
      // 大模型为主；仅当模型决策与明确写操作意图相悖时才用关键词纠正（安全网，不抢占）。
      return avoidRepeatedCompletedExecutor(correct(decision, request, subagents, tools), state);
    } catch (Exception error) {
      return avoidRepeatedCompletedExecutor(fallback(request, hasDocuments, subagents, state, arguments, error), state);
    }
  }

  /**
   * 大模型路由后的安全网：仅当模型决策与明确的领域意图相悖时才纠正。
   * learning / diet / travel / planning 各自由对应识别函数决定，互斥。
   */
  private Decision correct(Decision decision, String request,
                           com.changlu.planner.agent.core.registry.SubagentRegistry subagents,
                           com.changlu.planner.agent.core.tool.ToolRegistry tools) {
    boolean learningWrite = subagents.contains("learning") && isLearningGoalWrite(request);
    boolean dietRequest = subagents.contains("diet") && isDietRequest(request);
    boolean travelRequest = subagents.contains("travel") && isTravelRequest(request);
    boolean planTodoWrite = tools.contains("planning.assistant") && isPlanTodoWrite(request);
    if (learningWrite && !isLearningDecision(decision)) {
      return Decision.execute("subagent", "learning",
          decision.action() == Action.EXECUTE ? "模型路由与明确学习目标写操作意图相悖，纠正到 learning"
              : "模型对明确学习目标写操作直接完成，纠正到 learning 生成草案");
    }
    // 饮食/旅行先于 planning 检查：否则"安排饮食计划/安排旅行计划"会被 planning 抢走。
    if (dietRequest && !isDietDecision(decision)) {
      return Decision.execute("subagent", "diet",
          "模型路由与明确饮食规划意图相悖，纠正到 diet");
    }
    if (travelRequest && !isTravelDecision(decision)) {
      return Decision.execute("subagent", "travel",
          "模型路由与明确旅行规划意图相悖，纠正到 travel");
    }
    if (planTodoWrite && !isPlanningDecision(decision)) {
      return Decision.execute("tool", "planning.assistant",
          decision.action() == Action.EXECUTE ? "模型路由与明确计划/待办写操作意图相悖，纠正到 planning.assistant"
              : "模型对明确计划/待办写操作直接完成，纠正到 planning.assistant");
    }
    return decision;
  }

  private boolean isLearningDecision(Decision decision) {
    return decision.action() == Action.EXECUTE && "subagent".equals(decision.executorType())
        && "learning".equals(decision.executorName());
  }

  private boolean isPlanningDecision(Decision decision) {
    return decision.action() == Action.EXECUTE && "tool".equals(decision.executorType())
        && "planning.assistant".equals(decision.executorName());
  }

  private boolean isDietDecision(Decision decision) {
    return decision.action() == Action.EXECUTE && "subagent".equals(decision.executorType())
        && "diet".equals(decision.executorName());
  }

  private boolean isTravelDecision(Decision decision) {
    return decision.action() == Action.EXECUTE && "subagent".equals(decision.executorType())
        && "travel".equals(decision.executorName());
  }

  /** 明确的饮食规划意图（与 fallback 的关键词一致）。 */
  private boolean isDietRequest(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    return normalized.contains("健康饮食") || normalized.contains("饮食计划")
        || normalized.contains("饮食") || normalized.contains("减脂")
        || normalized.contains("减肥") || normalized.contains("增肌")
        || normalized.contains("健身餐") || normalized.contains("一周食谱")
        || normalized.contains("每日菜单") || normalized.contains("控糖")
        || normalized.contains("营养搭配") || normalized.contains("食谱推荐")
        || normalized.contains("食物热量");
  }

  private boolean isTravelRequest(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    return normalized.contains("旅行") || normalized.contains("旅游")
        || normalized.contains("行程") || normalized.contains("之旅");
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
                            JsonObject arguments,
                            Exception error) {
    String normalized = request.replaceAll("\\s", "");
    if (travelRevisionIntent(request, state, arguments, subagents)) {
      return Decision.execute("subagent", "travel", "模型路由失败，按旅行方案修改上下文回退");
    }
    if (normalized.contains("健康饮食") || normalized.contains("饮食计划") || normalized.contains("减脂餐")
        || normalized.contains("减肥餐") || normalized.contains("增肌餐") || normalized.contains("健身餐")
        || normalized.contains("一周食谱") || normalized.contains("每日菜单") || normalized.contains("控糖饮食")
        || normalized.contains("营养搭配") || normalized.contains("食谱推荐") || normalized.contains("食物热量")) {
      return Decision.execute("subagent", "diet", "模型路由失败，按明确健康饮食意图回退");
    }
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
        || normalized.contains("学习计划") || normalized.contains("学习进度")
        || normalized.contains("学习目标") || normalized.contains("目标日期")
        || normalized.contains("目标")
        || normalized.contains("想学") || normalized.contains("要学")
        || normalized.contains("想考") || normalized.contains("要考")) {
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

  /**
   * 学习目标写操作（创建/修改/删除）的确定性识别。
   * 动词与 {@link com.changlu.planner.agent.subagents.learning.LearningSubagent} 的意图分类保持一致，
   * 且必须落在学习目标/考试语境里，避免抢走普通规划、文件、行程等领域的写操作。
   */
  private boolean isLearningGoalWrite(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    // 明确针对其他实体（待办/任务/日程/文件/文档/附件/行程）的操作，归对应领域，不是学习目标。
    if (normalized.contains("文件") || normalized.contains("文档") || normalized.contains("附件")
        || normalized.contains("待办") || normalized.contains("任务")
        || normalized.contains("日程") || normalized.contains("行程")) {
      return false;
    }
    // 目标是"计划"（而非"学习目标/目标"）→ 归 planning，不是学习目标写操作。
    // 例：「删除雅思的长期计划」删的是计划；「删除雅思学习目标」删的是目标。
    if (normalized.contains("计划") && !normalized.contains("学习目标")
        && !normalized.contains("目标")) {
      return false;
    }
    boolean writeOp = normalized.contains("创建") || normalized.contains("新建")
        || normalized.contains("添加目标") || normalized.contains("设立")
        || normalized.contains("修改") || normalized.contains("更新")
        || normalized.contains("调整目标") || normalized.contains("改到")
        || normalized.contains("改成") || normalized.contains("改一下")
        || normalized.contains("提前") || normalized.contains("推迟")
        || normalized.contains("顺延")
        || normalized.contains("删除") || normalized.contains("移除")
        || normalized.contains("放弃");
    // 无"创建"字样但表达"想学/要学X，达到/考到Y分"的创建目标意图，同样确定性派发到 learning。
    boolean wantsLearn = normalized.contains("想学") || normalized.contains("要学")
        || normalized.contains("打算学") || normalized.contains("准备学")
        || normalized.contains("开始学") || normalized.contains("想考")
        || normalized.contains("要考");
    boolean hasTarget = normalized.contains("达到") || normalized.contains("考到")
        || normalized.contains("分") || normalized.contains("目标");
    boolean question = normalized.contains("怎么学") || normalized.contains("怎么")
        || normalized.contains("如何") || normalized.contains("吗")
        || normalized.contains("？") || normalized.contains("?");
    boolean createIntent = wantsLearn && hasTarget && !question;
    if (!writeOp && !createIntent) return false;
    if (createIntent) return true;
    // 学习目标/考试语境：明确"学习目标"，或"目标"与学习相关词同现，或具体考试/技能名称。
    return normalized.contains("学习目标") || normalized.contains("课程目标")
        || (normalized.contains("目标") && normalized.contains("学习"))
        || normalized.contains("备考") || normalized.contains("考到")
        || normalized.contains("雅思") || normalized.contains("托福")
        || normalized.contains("四六级") || normalized.contains("六级")
        || normalized.contains("考研") || normalized.contains("gre")
        || normalized.contains("证书") || normalized.contains("学会")
        || normalized.contains("掌握");
  }

  /**
   * 计划/待办/任务/日程写操作的确定性识别。
   * 与学习目标互斥：明确目标是计划（非"学习目标/学习计划"）、待办、任务、日程时归 planning.assistant。
   */
  private boolean isPlanTodoWrite(String request) {
    String normalized = request.replaceAll("\\s", "").toLowerCase();
    boolean writeOp = normalized.contains("创建") || normalized.contains("新建")
        || normalized.contains("新增") || normalized.contains("添加")
        || normalized.contains("修改") || normalized.contains("更新")
        || normalized.contains("调整") || normalized.contains("改到")
        || normalized.contains("改成") || normalized.contains("改一下")
        || normalized.contains("删除") || normalized.contains("移除")
        || normalized.contains("完成") || normalized.contains("推迟")
        || normalized.contains("提前") || normalized.contains("安排")
        || normalized.contains("排进");
    if (!writeOp) return false;
    // 饮食/旅行/学习计划等专业领域不归 planning.assistant，交给对应 subagent 或模型路由。
    // 否则"安排饮食计划/安排旅行计划"会被 planning 抢走。
    if (isDietRequest(request) || isTravelRequest(request)
        || normalized.contains("学习目标") || normalized.contains("课程目标")
        || normalized.contains("学习计划") || normalized.contains("学习方案")) {
      return false;
    }
    // 明确目标是待办/任务/日程，或普通"计划"（非学习/饮食/旅行计划）。
    if (normalized.contains("待办") || normalized.contains("任务")
        || normalized.contains("日程") || normalized.contains("事项")) {
      return true;
    }
    if (normalized.contains("计划") && !normalized.contains("学习计划")
        && !normalized.contains("学习方案")) {
      return true;
    }
    return false;
  }

  private boolean planningIntent(String request) {
    return request.contains("创建") || request.contains("安排") || request.contains("布置")
        || request.contains("生成任务") || request.contains("制定计划") || request.contains("加入待办")
        || request.contains("排进日程") || request.contains("调整计划");
  }

  /** 判断用户是否正在修改已经生成过的旅行方案，而不是发起普通计划调整。 */
  private boolean travelRevisionIntent(String request,
                                       com.changlu.planner.agent.core.contract.AgentLoopState state,
                                       JsonObject arguments,
                                       com.changlu.planner.agent.core.registry.SubagentRegistry subagents) {
    if (!subagents.contains("travel") || request == null) return false;
    String normalized = request.replaceAll("\\s", "");
    boolean dayReference = normalized.matches(".*第[一二三四五六七八九十两0-9]+天.*")
        || normalized.matches(".*20\\d{2}-\\d{2}-\\d{2}.*");
    boolean change = normalized.contains("修改") || normalized.contains("改成") || normalized.contains("调整")
        || normalized.contains("替换") || normalized.contains("换成") || normalized.contains("改为");
    if (!dayReference || !change) return false;
    boolean suppliedTravelPlan = arguments != null && arguments.has("previousTravelData")
        && arguments.get("previousTravelData").isJsonObject();
    boolean stateHasTravelPlan = state != null && state.taskData.has("days") && state.taskData.has("request");
    if (suppliedTravelPlan || stateHasTravelPlan) return true;
    return normalized.contains("旅行") || normalized.contains("旅游") || normalized.contains("行程")
        || normalized.contains("景点") || normalized.contains("海底世界") || normalized.contains("爬山")
        || normalized.contains("海边") || normalized.contains("海滨") || normalized.contains("沙滩")
        || normalized.contains("酒店") || normalized.contains("民宿") || normalized.contains("门票")
        || normalized.contains("登山") || normalized.contains("海洋馆");
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
