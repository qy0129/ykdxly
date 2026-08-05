package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.travel.tools.DestinationResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelDraftTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Domain orchestrator only. External access and business writes are delegated to registered Tools. */
public final class TravelSubagent implements Subagent {
  private final TravelPlannerModel planner;
  private final ToolRegistry tools;
  private final TravelPolicy policy;
  private final SubagentDefinition definition;

  public TravelSubagent(TravelPlannerModel planner, ToolRegistry tools, TravelPolicy policy,
                        JsonObject inputSchema, JsonObject outputSchema) {
    this.planner = planner;
    this.tools = tools;
    this.policy = policy;
    this.definition = new SubagentDefinition("travel", "1.0.0",
        "把旅行需求整理为按天可执行的行程，并在用户要求时生成写入计划 App 的待确认草案",
        List.of("旅游", "旅行", "行程规划", "目的地研究", "旅行需求澄清", "按天行程规划", "准备清单", "预算估算", "写入计划草案"),
        List.of("自动订票", "自动付款", "自动预订酒店或门票", "保证实时价格和营业时间"),
        inputSchema, outputSchema, Set.of(DestinationResearchTool.NAME, TravelDraftTool.NAME),
        true, true, Duration.ofSeconds(180), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    request = policy.normalizeRequest(request);
    try {
      policy.validateInput(request);
    } catch (IllegalArgumentException error) {
      return informationForm(request, fieldFromError(error.getMessage()), context);
    }
    if (policy.unsupportedRequest(request.message())) {
      return AgentResult.failed("TRAVEL_UNSUPPORTED_OPERATION",
          "我可以生成预订和购票任务，但不能代你订票、付款或完成外部预订。", false, context.traceId());
    }
    JsonObject researchArguments = new JsonObject();
    researchArguments.addProperty("query", researchQuery(request));
    JsonArray sources = new JsonArray();
    boolean researchUnavailable = false;
    try {
      AgentResult research = tools.execute(new ToolCall(context.runId() + ":travel:research", null,
          DestinationResearchTool.NAME, researchArguments), context);
      if (research.data().has("sources") && research.data().get("sources").isJsonArray()) {
        sources = research.data().getAsJsonArray("sources");
      }
    } catch (SecurityException | IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      researchUnavailable = true;
    }

    JsonObject previousData = previousTravelData(context);
    boolean planApproved = policy.planApproved(request.message(), request.arguments());
    JsonObject generated;
    if (planApproved && previousData != null && policy.pureApproval(request.message())) {
      // 纯确认语句（无修改内容）直接复用第一阶段数据，避免模型把确认语句当成新需求重新追问。
      // 确认语里携带修改（如"确认行程，但第三天改成…"）时走重新生成，让模型吸收修改。
      generated = previousData.deepCopy();
    } else {
      try {
        generated = planner.plan(request, sources, context.sharedContext());
      } catch (Exception error) {
        generated = fallbackPlan(request, sources, error);
      }
    }
    TravelResult travel = TravelResult.fromGenerated(generated, sources);
    policy.validate(travel);
    JsonObject data = travel.toData();
    JsonArray missingRequirements = missingRequirements(travel);
    if (!missingRequirements.isEmpty()) return informationForm(data, missingRequirements, context);
    if (researchUnavailable) {
      JsonObject risk = new JsonObject();
      risk.addProperty("code", "EXTERNAL_SERVICE_UNAVAILABLE");
      risk.addProperty("message", "公开资料暂时不可用，价格、开放时间和交通信息需要再次核实。");
      risk.addProperty("verificationRequired", true);
      data.getAsJsonArray("risks").add(risk);
    }
    // 出发城市只影响往返交通，不应阻塞已经具备目的地、日期和逐日明细的行程。
    // 这类信息保留在 questions 中，交给用户在确认草案前补充。
    boolean missingCoreSchedule = travel.request().destination().isBlank()
        || travel.request().startDate().isBlank()
        || travel.request().endDate().isBlank()
        || travel.days().isEmpty();
    if (!travel.questions().isEmpty() && missingCoreSchedule) {
      return AgentResult.waitingUser(travel.message(), data, context.traceId());
    }
    // 用户已确认方案（planApproved）视为要求写入，避免裸"确认行程"只回 completed 而实际没保存。
    if (!planApproved && !policy.writeRequested(request.message(), request.arguments())) {
      return AgentResult.completed(travel.message(), data, context.traceId());
    }

    // 旅行先进入可修改的方案审阅阶段，用户确认后才创建写入草案。
    if (!planApproved) {
      JsonArray questions = data.has("questions") && data.get("questions").isJsonArray()
          ? data.getAsJsonArray("questions") : new JsonArray();
      questions.add("请检查以上行程；确认无误后点击“确认行程，生成写入草案”，也可以直接输入修改意见。");
      data.add("questions", questions);
      data.addProperty("planReview", true);
      data.addProperty("planApprovalRequired", true);
      return AgentResult.waitingUser(travel.message(), data, context.traceId());
    }

    JsonObject draftArguments = new JsonObject();
    String planningInstruction = travel.planningInstruction();
    if (planningInstruction == null || planningInstruction.isBlank()) {
      planningInstruction = "创建一个" + travel.request().destination() + "旅行计划，按日期写入逐日行程和出行准备任务。";
    }
    draftArguments.addProperty("planningInstruction", planningInstruction);
    // 始终用当前方案 data（含用户修改后的重新生成结果），不再回退到旧 previousData。
    draftArguments.add("travelData", data.deepCopy());
    AgentResult draft = tools.execute(new ToolCall(context.runId() + ":travel:draft",
        context.runId() + ":travel-plan", TravelDraftTool.NAME, draftArguments), context);
    JsonObject merged = data.deepCopy();
    for (String key : draft.data().keySet()) merged.add(key, draft.data().get(key).deepCopy());
    return new AgentResult("1.0", draft.status(), draft.message(), merged, draft.errors(), context.traceId(),
        draft.requiresConfirmation(), draft.draftId());
  }

  private String researchQuery(SubagentRequest request) {
    String destination = request.arguments().has("destination")
        ? request.arguments().get("destination").getAsString().trim() : "";
    String base = destination.isBlank() ? request.message() : destination;
    return base + " 旅行 景点 交通 开放时间 注意事项 最新";
  }

  private JsonArray missingRequirements(TravelResult travel) {
    JsonArray requirements = new JsonArray();
    JsonObject requestData = travel.request().toJson();
    addRequirement(requirements, requestData, "destination", "目的地", "text", true);
    addRequirement(requirements, requestData, "startDate", "出发日期", "date", true);
    addRequirement(requirements, requestData, "endDate", "结束日期", "date", true);
    return requirements;
  }

  private AgentResult informationForm(SubagentRequest request, String field, AgentContext context) {
    JsonArray requirements = new JsonArray();
    addRequirement(requirements, request.arguments(), field, fieldLabel(field), fieldType(field), true);
    return informationForm(request, requirements, context);
  }

  private AgentResult informationForm(SubagentRequest request, JsonArray requirements, AgentContext context) {
    JsonObject data = new JsonObject();
    data.add("request", request.arguments().deepCopy());
    return informationForm(data, requirements, context);
  }

  private AgentResult informationForm(JsonObject data, JsonArray requirements, AgentContext context) {
    data.addProperty("formTitle", "信息搜集表");
    data.add("inputRequirements", requirements);
    JsonArray questions = new JsonArray();
    questions.add("请填写信息搜集表，AI 会结合表单和备注生成旅行计划。");
    data.add("questions", questions);
    return AgentResult.waitingUser("请先补充旅行信息。", data, context.traceId());
  }

  private void addRequirement(JsonArray requirements, JsonObject arguments, String field,
                              String label, String type, boolean required) {
    if (arguments.has(field) && !arguments.get(field).isJsonNull()) {
      JsonElement value = arguments.get(field);
      boolean filled = value.isJsonPrimitive() ? !value.getAsString().isBlank()
          : value.isJsonObject() ? !value.getAsJsonObject().keySet().isEmpty()
          : value.isJsonArray() && value.getAsJsonArray().size() > 0;
      if (filled) return;
    }
    JsonObject item = new JsonObject();
    item.addProperty("field", field);
    item.addProperty("label", label);
    item.addProperty("type", type);
    item.addProperty("required", required);
    requirements.add(item);
  }

  private String fieldFromError(String message) {
    if (message == null) return "remarks";
    int index = message.lastIndexOf('.');
    String field = index >= 0 ? message.substring(index + 1) : message.replace("INVALID_ARGUMENT:", "");
    return field.isBlank() ? "remarks" : field;
  }

  private String fieldLabel(String field) {
    return switch (field) {
      case "destination" -> "目的地";
      case "origin" -> "出发地";
      case "startDate" -> "出发日期";
      case "endDate" -> "结束日期";
      case "travelers" -> "出行人数";
      case "pace" -> "旅行节奏";
      case "budget" -> "预算";
      default -> "补充信息";
    };
  }

  private String fieldType(String field) {
    return switch (field) {
      case "startDate", "endDate" -> "date";
      case "travelers" -> "number";
      case "pace" -> "select";
      case "budget" -> "number";
      default -> "text";
    };
  }

  private JsonObject previousTravelData(AgentContext context) {
    JsonObject state = context.taskState();
    if (!state.has("taskData") || !state.get("taskData").isJsonObject()) return null;
    JsonObject taskData = state.getAsJsonObject("taskData");
    if (!taskData.has("days") || !taskData.get("days").isJsonArray()) return null;
    return taskData.deepCopy();
  }

  private JsonObject fallbackPlan(SubagentRequest request, JsonArray sources, Exception error) {
    TravelRequest travel = TravelRequest.from(request.arguments());
    JsonObject result = new JsonObject();
    result.addProperty("message", "旅游模型暂时不可用，已根据你的日期、节奏和目的地生成基础行程；实时价格、开放时间和交通信息请出发前核实。");
    result.add("request", travel.toJson());
    JsonArray days = new JsonArray();
    if (!travel.startDate().isBlank() && !travel.endDate().isBlank()) {
      java.time.LocalDate start = java.time.LocalDate.parse(travel.startDate());
      java.time.LocalDate end = java.time.LocalDate.parse(travel.endDate());
      int index = 0;
      for (java.time.LocalDate date = start; !date.isAfter(end) && index < 30; date = date.plusDays(1), index++) {
        JsonObject day = new JsonObject();
        day.addProperty("date", date.toString());
        day.addProperty("title", index == 0 ? "抵达与海边慢游" : date.equals(end) ? "返程整理" : "海边休闲日");
        JsonArray activities = new JsonArray();
        activity(activities, "上午", "睡到自然醒，沿海边散步", travel.destination());
        activity(activities, "下午", "选择一个附近景点或海湾慢慢游览", travel.destination());
        activity(activities, "晚上", "在海边附近用餐，保留自由时间", travel.destination());
        day.add("activities", activities);
        days.add(day);
      }
    }
    result.add("days", days);
    JsonArray preparation = new JsonArray();
    task(preparation, "确认交通和住宿信息", "high");
    task(preparation, "准备身份证件、防晒用品和舒适鞋服", "medium");
    result.add("preparationTasks", preparation);
    JsonObject budget = travel.budget().deepCopy();
    if (!budget.has("currency")) budget.addProperty("currency", "CNY");
    if (!budget.has("amount")) budget.addProperty("amount", 0);
    budget.addProperty("estimated", true);
    budget.add("breakdown", new JsonArray());
    result.add("budgetEstimate", budget);
    JsonArray risks = new JsonArray();
    JsonObject risk = new JsonObject();
    risk.addProperty("code", "MODEL_UNAVAILABLE");
    risk.addProperty("message", "旅行模型响应超时或暂时不可用，当前为规则降级方案。");
    risk.addProperty("verificationRequired", true);
    risks.add(risk);
    result.add("risks", risks);
    result.add("questions", new JsonArray());
    result.addProperty("planningInstruction", "请创建一个旅行计划，按出发前准备、每日行程、返程整理拆分阶段和任务，并生成对应日程草案。");
    return result;
  }

  private void activity(JsonArray activities, String time, String title, String location) {
    JsonObject activity = new JsonObject();
    activity.addProperty("startTime", "");
    activity.addProperty("durationMinutes", 120);
    activity.addProperty("title", time + "：" + title);
    activity.addProperty("location", location);
    activity.addProperty("notes", "按体力和天气灵活调整");
    activities.add(activity);
  }

  private void task(JsonArray tasks, String title, String priority) {
    JsonObject task = new JsonObject();
    task.addProperty("title", title);
    task.addProperty("dueAt", "");
    task.addProperty("priority", priority);
    tasks.add(task);
  }
}
