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
import com.changlu.planner.agent.subagents.travel.tools.LocationContextTool;
import com.changlu.planner.agent.subagents.travel.tools.WeatherForecastTool;
import com.changlu.planner.agent.subagents.travel.tools.AttractionResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.MapRoutingTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Domain orchestrator only. External access and business writes are delegated to registered Tools. */
public final class TravelSubagent implements Subagent {
  private static final Logger LOG = LoggerFactory.getLogger(TravelSubagent.class);
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
        inputSchema, outputSchema, Set.of(DestinationResearchTool.NAME, LocationContextTool.NAME,
            WeatherForecastTool.NAME, AttractionResearchTool.NAME, MapRoutingTool.NAME, TravelDraftTool.NAME),
        true, true, Duration.ofSeconds(180), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    request = policy.normalizeRequest(request);
    String attractionQuery = TravelPlanRevision.attractionQuery(request.message());
    if (!attractionQuery.isBlank()) request.arguments().addProperty("attractionQuery", attractionQuery);
    try {
      policy.validateInput(request);
    } catch (IllegalArgumentException error) {
      return informationForm(request, fieldFromError(error.getMessage()), context);
    }
    if (policy.unsupportedRequest(request.message())) {
      return AgentResult.failed("TRAVEL_UNSUPPORTED_OPERATION",
          "我可以生成预订和购票任务，但不能代你订票、付款或完成外部预订。", false, context.traceId());
    }
    JsonArray requiredBeforeResearch = new JsonArray();
    addRequirement(requiredBeforeResearch, request.arguments(), "destination", "目的地", "text", true);
    addRequirement(requiredBeforeResearch, request.arguments(), "startDate", "出发日期", "date", true);
    addRequirement(requiredBeforeResearch, request.arguments(), "endDate", "结束日期", "date", true);
    if (!requiredBeforeResearch.isEmpty()) return informationForm(request, requiredBeforeResearch, context);

    TravelDataCollector collector = new TravelDataCollector(tools);
    TravelDataCollector.Collected collected = collector.collect(
        new TravelDataCollector.SubagentRequestView(request.arguments(), researchQuery(request)), context);
    JsonObject facts = collected.facts();
    JsonArray sources = facts.getAsJsonArray("sources");

    JsonObject previousData = previousTravelData(context, request.arguments());
    SubagentRequest plannerRequest = new SubagentRequest(request.message(), authoritativeRequest(request.arguments()),
        request.documentIds());
    boolean planApproved = policy.planApproved(request.message(), request.arguments());
    boolean reusePreviousPlan = planApproved && previousData != null && policy.pureApproval(request.message());
    // 用户修订时若改变了目的地/日期范围，旧方案的天数不能再套用（否则产出"目的地=北京、行程=三亚"的错乱计划），
    // 必须走全量重新生成。
    boolean scopeChanged = previousData != null && changedTripScope(request.arguments(), previousData);
    JsonObject localizedRevision = previousData != null && !scopeChanged && !policy.pureApproval(request.message())
        ? TravelPlanRevision.apply(previousData, request.message(), facts.getAsJsonArray("attractions")) : null;
    JsonObject generated;
    if (reusePreviousPlan) {
      // 纯确认语句（无修改内容）直接复用第一阶段数据，避免模型把确认语句当成新需求重新追问。
      // 确认语里携带修改（如"确认行程，但第三天改成…"）时走重新生成，让模型吸收修改。
      generated = previousData.deepCopy();
    } else if (localizedRevision != null) {
      generated = localizedRevision;
    } else {
      try {
        generated = planner.plan(plannerRequest, plannerFacts(facts), context.sharedContext());
      } catch (Exception error) {
        LOG.warn("[旅行模型降级] trace={} type={} message={}", context.traceId(),
            error.getClass().getSimpleName(), error.getMessage());
        generated = fallbackPlan(plannerRequest, sources, facts.getAsJsonArray("attractions"), error);
      }
    }
    // The model plans only the itinerary. The normalized request is authoritative and does not need to be echoed back.
    if (!reusePreviousPlan) generated.add("request", authoritativeRequest(request.arguments()));
    TravelRequest generatedRequest = TravelRequest.from(generated.getAsJsonObject("request"));
    ItineraryOptimizer.Result optimized = new ItineraryOptimizer().optimize(generatedRequest,
        generated.has("days") && generated.get("days").isJsonArray() ? generated.getAsJsonArray("days") : new JsonArray(),
        facts.getAsJsonArray("weather"), facts.getAsJsonArray("attractions"));
    generated.add("days", optimized.days());
    generated.add("budgetEstimate", new BudgetEngine().estimate(generatedRequest));
    JsonArray generatedRisks = generated.has("risks") && generated.get("risks").isJsonArray()
        ? generated.getAsJsonArray("risks") : new JsonArray();
    for (JsonElement conflict : optimized.conflicts()) {
      JsonObject risk = conflict.getAsJsonObject().deepCopy(); risk.addProperty("verificationRequired", true); generatedRisks.add(risk);
    }
    generated.add("risks", generatedRisks);
    TravelResult travel = TravelResult.fromGenerated(generated, sources);
    policy.validate(travel);
    JsonObject data = travel.toData();
    if (localizedRevision != null) {
      data.addProperty("revisionMode", "localized");
      data.addProperty("revisionSummary", "已根据指定日期或时段更新行程，其余天数保持不变。");
    }
    if (localizedRevision != null && localizedRevision.has("revisionDiff")
        && localizedRevision.get("revisionDiff").isJsonArray()) {
      data.add("revisionDiff", localizedRevision.getAsJsonArray("revisionDiff").deepCopy());
    }
    data.add("locationContext", facts.getAsJsonObject("locationContext").deepCopy());
    data.add("weather", facts.getAsJsonArray("weather").deepCopy());
    data.add("attractions", facts.getAsJsonArray("attractions").deepCopy());
    // 路线矩阵是锦上添花的富化，不是核心：剩余预算不足 35s 时跳过，避免 collect+模型+路线 超 180s 总 deadline。
    long remainingBudgetMs = context.deadline().toEpochMilli() - System.currentTimeMillis();
    JsonArray transitMatrix = remainingBudgetMs >= 35_000
        ? collector.routes(generated, context, collected.risks())
        : new JsonArray();
    data.add("transitMatrix", transitMatrix);
    data.getAsJsonArray("risks").addAll(collected.risks());
    if (localizedRevision != null) addOpeningHoursVerificationRisk(data);
    addIntercityTransportRisk(travel.request(), data.getAsJsonArray("risks"));
    // 仅局部修订时活动时间重叠才阻塞草案（用户需先调整）；全新行程的重叠作为风险提示，不拦确认，避免确认死循环。
    if (localizedRevision != null && hasBlockingScheduleConflict(data.getAsJsonArray("risks"))) {
      JsonArray questions = data.has("questions") && data.get("questions").isJsonArray()
          ? data.getAsJsonArray("questions") : new JsonArray();
      questions.add("\u4fee\u6539\u540e\u7684\u6d3b\u52a8\u65f6\u95f4\u5b58\u5728\u91cd\u53e0\uff0c\u8bf7\u8c03\u6574\u65f6\u95f4\u540e\u518d\u751f\u6210\u5199\u5165\u8349\u6848\u3002");
      data.add("questions", questions);
      data.addProperty("planReview", true);
      data.addProperty("planApprovalRequired", true);
      return AgentResult.waitingUser("\u5c40\u90e8\u4fee\u6539\u5df2\u5e94\u7528\uff0c\u4f46\u6d3b\u52a8\u65f6\u95f4\u51b2\u7a81\uff0c\u6682\u4e0d\u80fd\u751f\u6210\u5199\u5165\u8349\u6848\u3002", data, context.traceId());
    }
    JsonArray missingRequirements = missingRequirements(travel);
    if (!missingRequirements.isEmpty()) return informationForm(data, missingRequirements, context);
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
      return AgentResult.completed(replyMessage(travel, localizedRevision), data, context.traceId());
    }

    // 旅行先进入可修改的方案审阅阶段，用户确认后才创建写入草案。
    if (!planApproved) {
      JsonArray questions = data.has("questions") && data.get("questions").isJsonArray()
          ? data.getAsJsonArray("questions") : new JsonArray();
      questions.add("请检查以上行程；确认无误后点击“确认行程，生成写入草案”，也可以直接输入修改意见。");
      data.add("questions", questions);
      data.addProperty("planReview", true);
      data.addProperty("planApprovalRequired", true);
      return AgentResult.waitingUser(replyMessage(travel, localizedRevision), data, context.traceId());
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
    return base + " " + request.message() + " 旅行 景点 交通 开放时间 注意事项 最新";
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

  private JsonObject previousTravelData(AgentContext context, JsonObject arguments) {
    if (arguments.has("previousTravelData") && arguments.get("previousTravelData").isJsonObject()) {
      JsonObject previous = arguments.getAsJsonObject("previousTravelData");
      if (hasDaysAndRequest(previous)) return previous.deepCopy();
    }
    JsonObject state = context.taskState();
    if (!state.has("taskData") || !state.get("taskData").isJsonObject()) return null;
    JsonObject taskData = state.getAsJsonObject("taskData");
    if (!hasDaysAndRequest(taskData)) return null;
    return taskData.deepCopy();
  }

  /** 只有同时具备 days 与 request 的旧方案才能用于复用/局部修订，否则 TravelRequest.from 会 NPE。 */
  private boolean hasDaysAndRequest(JsonObject value) {
    return value.has("days") && value.get("days").isJsonArray()
        && value.has("request") && value.get("request").isJsonObject();
  }

  /** 修订消息若改变了目的地或日期范围，旧方案的天数不能再套用，必须全量重生成。 */
  private boolean changedTripScope(JsonObject arguments, JsonObject previousData) {
    if (!previousData.has("request") || !previousData.get("request").isJsonObject()) return true;
    JsonObject previous = previousData.getAsJsonObject("request");
    JsonObject current = authoritativeRequest(arguments);
    return !sameRequestField(current, previous, "destination")
        || !sameRequestField(current, previous, "startDate")
        || !sameRequestField(current, previous, "endDate");
  }

  private boolean sameRequestField(JsonObject current, JsonObject previous, String field) {
    String a = current.has(field) && !current.get(field).isJsonNull() ? current.get(field).getAsString().trim() : "";
    String b = previous.has(field) && !previous.get(field).isJsonNull() ? previous.get(field).getAsString().trim() : "";
    return a.equals(b);
  }

  private JsonObject authoritativeRequest(JsonObject arguments) {
    JsonObject request = arguments.deepCopy();
    request.remove("previousTravelData");
    request.remove("attractionQuery");
    return request;
  }

  private String replyMessage(TravelResult travel, JsonObject localizedRevision) {
    return localizedRevision == null ? travel.message() : "已根据你的局部修改更新行程，并重新检索了目的地资料。";
  }

  private boolean hasBlockingScheduleConflict(JsonArray risks) {
    for (JsonElement element : risks) {
      if (element.isJsonObject() && "ACTIVITY_TIME_OVERLAP".equals(string(element.getAsJsonObject(), "code", ""))) {
        return true;
      }
    }
    return false;
  }

  private void addOpeningHoursVerificationRisk(JsonObject data) {
    JsonArray days = data.has("days") && data.get("days").isJsonArray() ? data.getAsJsonArray("days") : new JsonArray();
    for (JsonElement dayElement : days) {
      if (!dayElement.isJsonObject()) continue;
      JsonObject day = dayElement.getAsJsonObject();
      JsonArray activities = day.has("activities") && day.get("activities").isJsonArray()
          ? day.getAsJsonArray("activities") : new JsonArray();
      for (JsonElement activityElement : activities) {
        if (!activityElement.isJsonObject()) continue;
        JsonObject activity = activityElement.getAsJsonObject();
        if (string(activity, "attractionId", "").isBlank() || !string(activity, "openingHours", "").isBlank()) continue;
        JsonObject risk = new JsonObject();
        risk.addProperty("code", "OPENING_HOURS_UNVERIFIED");
        risk.addProperty("date", string(day, "date", ""));
        risk.addProperty("detail", string(activity, "title", ""));
        risk.addProperty("message", "\u666f\u70b9\u5f00\u653e\u65f6\u95f4\u6682\u65e0\u53ef\u9a8c\u8bc1\u7684\u6570\u636e\u6e90\uff0c\u8bf7\u51fa\u53d1\u524d\u901a\u8fc7\u5b98\u65b9\u6e20\u9053\u6838\u5b9e\u3002");
        risk.addProperty("verificationRequired", true);
        data.getAsJsonArray("risks").add(risk);
        return;
      }
    }
  }

  /** Keep the planner prompt bounded; the full external facts remain in the returned travel data. */
  JsonObject plannerFacts(JsonObject facts) {
    JsonObject compact = new JsonObject();
    if (facts.has("locationContext") && facts.get("locationContext").isJsonObject()) {
      compact.add("locationContext", facts.get("locationContext").deepCopy());
    }
    copyArray(compact, facts, "weather", 10, null);
    copyArray(compact, facts, "attractions", 12,
        Set.of("attractionId", "name", "address", "lat", "lng", "coordinateSystem",
            "openingHours", "requiresReservation", "sourceUrl", "evidenceText"));
    copyArray(compact, facts, "sources", 8, Set.of("title", "summary", "source", "url"));
    return compact;
  }

  private void copyArray(JsonObject target, JsonObject source, String name, int limit, Set<String> fields) {
    if (!source.has(name) || !source.get(name).isJsonArray()) return;
    JsonArray copied = new JsonArray();
    for (JsonElement element : source.getAsJsonArray(name)) {
      if (copied.size() >= limit) break;
      if (fields == null || !element.isJsonObject()) { copied.add(element.deepCopy()); continue; }
      JsonObject item = new JsonObject();
      for (String field : fields) if (element.getAsJsonObject().has(field)) {
        JsonElement value = element.getAsJsonObject().get(field);
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
          item.addProperty(field, truncate(value.getAsString(), fieldLimit(name, field)));
        } else {
          item.add(field, value.deepCopy());
        }
      }
      copied.add(item);
    }
    target.add(name, copied);
  }

  private int fieldLimit(String collection, String field) {
    if ("sources".equals(collection)) {
      return switch (field) {
        case "summary" -> 400;
        case "url" -> 500;
        case "title" -> 160;
        default -> 80;
      };
    }
    return switch (field) {
      case "evidenceText" -> 240;
      case "sourceUrl" -> 500;
      case "address" -> 160;
      case "openingHours" -> 120;
      case "name" -> 80;
      default -> 120;
    };
  }

  private String truncate(String value, int limit) {
    if (value == null || value.length() <= limit) return value == null ? "" : value;
    return value.substring(0, limit) + "...";
  }

  private void addIntercityTransportRisk(TravelRequest request, JsonArray risks) {
    if (!"highSpeedRail".equals(request.preferredTransport())) return;
    JsonObject risk = new JsonObject();
    risk.addProperty("code", "INTERCITY_TRANSPORT_VERIFICATION_REQUIRED");
    risk.addProperty("message", "当前未接入可靠的高铁直达班次数据，请核实出发地到目的地是否可直达；若不可行，建议比较飞机或分段交通，不会虚构班次。");
    risk.addProperty("verificationRequired", true);
    risks.add(risk);
  }

  private JsonObject fallbackPlan(SubagentRequest request, JsonArray sources, JsonArray attractions, Exception error) {
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
        day.addProperty("title", fallbackDayTitle(index, date.equals(end)));
        JsonArray activities = new JsonArray();
        activity(activities, "上午", "睡到自然醒后慢游", travel.destination(), attractionAt(attractions, index * 2));
        activity(activities, "下午", "安排一处景点或海湾慢游", travel.destination(), attractionAt(attractions, index * 2 + 1));
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
    String failureCategory = modelFailureCategory(error);
    risk.addProperty("causeCategory", failureCategory);
    risk.addProperty("message", switch (failureCategory) {
      case "configuration" -> "旅行模型的 API Key 未配置或认证失败，当前为规则降级方案。";
      case "timeout" -> "旅行模型响应超时，当前为规则降级方案。";
      case "network" -> "暂时无法连接旅行模型服务，当前为规则降级方案。";
      case "rate_limited" -> "旅行模型服务请求过于频繁，当前为规则降级方案。";
      case "invalid_response" -> "旅行模型返回格式不完整，当前为规则降级方案。";
      default -> "旅行模型服务暂时不可用，当前为规则降级方案。";
    });
    risk.addProperty("verificationRequired", true);
    risks.add(risk);
    result.add("risks", risks);
    result.add("questions", new JsonArray());
    result.addProperty("planningInstruction", "请创建一个旅行计划，按出发前准备、每日行程、返程整理拆分阶段和任务，并生成对应日程草案。");
    return result;
  }

  private String modelFailureCategory(Exception error) {
    String message = error.getMessage() == null ? "" : error.getMessage().toLowerCase(java.util.Locale.ROOT);
    if (error instanceof HttpTimeoutException || error instanceof TimeoutException
        || message.contains("timeout") || message.contains("timed out") || message.contains("超时")) return "timeout";
    if (error instanceof com.changlu.planner.agent.core.ModelClient.InvalidJsonException) return "invalid_response";
    if (message.contains("api_key") || message.contains("api key") || message.contains(" 401")
        || message.contains(" 403") || message.contains("未配置") || message.contains("认证")) return "configuration";
    if (message.contains(" 429") || message.contains("rate limit")) return "rate_limited";
    if (error instanceof ConnectException || error instanceof java.net.UnknownHostException
        || error instanceof java.net.SocketException) return "network";
    return "unavailable";
  }

  private String fallbackDayTitle(int index, boolean returnDay) {
    if (index == 0) return "抵达与海边慢游";
    if (returnDay) return "返程整理";
    String[] themes = {"老城与海湾漫步", "沙滩休闲日", "山海自然风景", "咖啡与观景慢游", "西海岸探索", "市区自由活动"};
    return themes[(index - 1) % themes.length];
  }

  private JsonObject attractionAt(JsonArray attractions, int index) {
    if (attractions == null || attractions.isEmpty()) return null;
    int size = attractions.size();
    for (int offset = 0; offset < size; offset++) {
      JsonElement value = attractions.get((index + offset) % size);
      if (!value.isJsonObject()) continue;
      JsonObject attraction = value.getAsJsonObject();
      if (attraction.has("attractionId") && attraction.has("name")) return attraction;
    }
    return null;
  }

  private void activity(JsonArray activities, String time, String title, String location) {
    activity(activities, time, title, location, null);
  }

  private void activity(JsonArray activities, String time, String title, String location, JsonObject attraction) {
    JsonObject activity = new JsonObject();
    activity.addProperty("startTime", "");
    // 三段式降级行程也必须满足 relaxed 的每日 240 分钟活动上限。
    activity.addProperty("durationMinutes", 75);
    String attractionName = attraction == null || !attraction.has("name") ? "" : attraction.get("name").getAsString();
    activity.addProperty("title", time + "：" + (attractionName.isBlank() ? title : attractionName + "慢游"));
    activity.addProperty("location", attraction == null || !attraction.has("address")
        ? location : attraction.get("address").getAsString());
    if (attraction != null && attraction.has("attractionId")) {
      activity.addProperty("attractionId", attraction.get("attractionId").getAsString());
      activity.addProperty("attractionName", attractionName);
    }
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

  private String string(JsonObject object, String name, String fallback) {
    if (object == null || !object.has(name) || object.get(name).isJsonNull()) return fallback;
    try { return object.get(name).getAsString(); }
    catch (RuntimeException ignored) { return fallback; }
  }
}
