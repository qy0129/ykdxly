package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.travel.tools.DestinationResearchTool;
import com.changlu.planner.agent.subagents.travel.tools.OpeningHoursTool;
import com.changlu.planner.agent.subagents.travel.tools.RouteEstimateTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelDraftTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelPlanValidationTool;
import com.changlu.planner.agent.subagents.travel.tools.TravelWeatherTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.LinkedHashSet;
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
    this.definition = new SubagentDefinition("travel", "1.1.0",
        "把旅行需求整理为按天可执行的行程，并在用户要求时生成写入计划 App 的待确认草案",
        List.of("旅游", "旅行", "行程规划", "目的地研究", "旅行需求澄清", "按天行程规划", "准备清单", "预算估算", "写入计划草案"),
        List.of("自动订票", "自动付款", "自动预订酒店或门票", "保证实时价格和营业时间"),
        inputSchema, outputSchema, Set.of(DestinationResearchTool.NAME, TravelWeatherTool.NAME,
            RouteEstimateTool.NAME, OpeningHoursTool.NAME, TravelPlanValidationTool.NAME, TravelDraftTool.NAME),
        true, true, Duration.ofSeconds(240), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    policy.validateInput(request);
    if (policy.unsupportedRequest(request.message())) {
      return AgentResult.failed("TRAVEL_UNSUPPORTED_OPERATION",
          "我可以生成预订和购票任务，但不能代你订票、付款或完成外部预订。", false, context.traceId());
    }
    JsonObject enrichedArguments = request.arguments().deepCopy();
    mergeMissingArguments(enrichedArguments, context.taskState());
    SubagentRequest effectiveRequest = new SubagentRequest(request.message(), enrichedArguments,
        request.documentIds());

    JsonObject researchArguments = new JsonObject();
    researchArguments.addProperty("query", researchQuery(effectiveRequest));
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

    TravelResult generated = TravelResult.fromGenerated(planner.plan(effectiveRequest, sources), sources);
    TravelRequest previous = TravelRequest.from(context.taskState());
    TravelRequest mergedRequest = TravelRequest.merge(previous, generated.request());
    TravelResult travel = generated.withRequestAndQuestions(mergedRequest, policy.missingQuestions(mergedRequest));
    policy.validate(travel);
    JsonArray enhancementRisks = new JsonArray();
    JsonObject initialData = travel.toData();
    if (researchUnavailable) {
      enhancementRisks.add(risk("EXTERNAL_SERVICE_UNAVAILABLE",
          "公开资料暂时不可用，价格、开放时间和交通信息需要再次核实。"));
    }
    if (!travel.questions().isEmpty()) {
      append(initialData.getAsJsonArray("risks"), enhancementRisks);
      return AgentResult.waitingUser(travel.message(), initialData, context.traceId());
    }

    JsonObject weatherData = optionalTool(TravelWeatherTool.NAME, weatherArguments(mergedRequest), context,
        enhancementRisks, "WEATHER_LOOKUP_UNAVAILABLE");
    JsonArray routeSegments = routeSegments(mergedRequest, travel.days());
    JsonObject routeData = routeSegments.isEmpty() ? new JsonObject() : optionalTool(RouteEstimateTool.NAME,
        objectWith("segments", routeSegments), context, enhancementRisks, "ROUTE_LOOKUP_UNAVAILABLE");
    JsonArray places = openingPlaces(mergedRequest, travel.days());
    JsonObject openingData = places.isEmpty() ? new JsonObject() : optionalTool(OpeningHoursTool.NAME,
        objectWith("places", places), context, enhancementRisks, "OPENING_HOURS_LOOKUP_UNAVAILABLE");

    JsonArray evidence = sources.deepCopy();
    addEvidence(evidence, "weather", weatherData);
    addEvidence(evidence, "routes", routeData);
    addEvidence(evidence, "opening_hours", openingData);
    SubagentRequest refinedRequest = new SubagentRequest(request.message(), mergedRequest.toJson(),
        request.documentIds());
    TravelResult refined = travel;
    try {
      JsonObject refinedGenerated = planner.plan(refinedRequest, evidence);
      TravelResult candidate = TravelResult.fromGenerated(refinedGenerated, sources);
      TravelRequest candidateRequest = TravelRequest.merge(mergedRequest, candidate.request());
      refined = candidate.withRequestAndQuestions(candidateRequest, policy.missingQuestions(candidateRequest));
      policy.validate(refined);
    } catch (Exception error) {
      enhancementRisks.add(risk("ENHANCED_PLANNING_UNAVAILABLE", "增强资料未能重新生成行程，已保留初始方案。"));
    }

    JsonObject validationArguments = new JsonObject();
    validationArguments.add("request", refined.request().toJson());
    validationArguments.add("days", refined.days().deepCopy());
    validationArguments.add("budgetEstimate", refined.budgetEstimate().deepCopy());
    validationArguments.add("routes", array(routeData, "routes"));
    validationArguments.add("openingHours", array(openingData, "openingHours"));
    JsonObject validationData = optionalTool(TravelPlanValidationTool.NAME, validationArguments, context,
        enhancementRisks, "TRAVEL_VALIDATION_UNAVAILABLE");
    JsonObject data = refined.toData();
    data.add("weather", object(weatherData, "weather"));
    data.add("routes", array(routeData, "routes"));
    data.add("openingHours", array(openingData, "openingHours"));
    data.add("validation", object(validationData, "validation"));
    appendValidationRisks(data.getAsJsonArray("risks"), validationData);
    append(data.getAsJsonArray("risks"), enhancementRisks);
    if (!policy.writeRequested(request.message(), request.arguments())) {
      return AgentResult.completed(refined.message(), data, context.traceId());
    }

    JsonObject draftArguments = new JsonObject();
    draftArguments.addProperty("planningInstruction", refined.planningInstruction());
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

  private void mergeMissingArguments(JsonObject arguments, JsonObject previous) {
    for (String name : List.of("destination", "origin", "startDate", "endDate", "travelers", "budget",
        "pace", "interests", "constraints")) {
      if ((!arguments.has(name) || arguments.get(name).isJsonNull()) && previous.has(name)) {
        arguments.add(name, previous.get(name).deepCopy());
      }
    }
  }

  private JsonObject optionalTool(String name, JsonObject arguments, AgentContext context,
                                  JsonArray risks, String errorCode) throws Exception {
    try {
      AgentResult result = tools.execute(new ToolCall(context.runId() + ":travel:" + name, null, name, arguments), context);
      return result.data();
    } catch (SecurityException | IllegalArgumentException error) {
      throw error;
    } catch (Exception error) {
      risks.add(risk(errorCode, error.getMessage() == null ? "外部资料不可用" : error.getMessage()));
      return new JsonObject();
    }
  }

  private JsonObject weatherArguments(TravelRequest request) {
    JsonArray locations = new JsonArray();
    LinkedHashSet<String> unique = new LinkedHashSet<>();
    if (!request.origin().isBlank()) unique.add(request.origin());
    if (!request.destination().isBlank()) unique.add(request.destination());
    unique.forEach(locations::add);
    JsonObject result = objectWith("locations", locations);
    result.addProperty("startDate", request.startDate());
    result.addProperty("endDate", request.endDate());
    return result;
  }

  private JsonArray routeSegments(TravelRequest request, JsonArray days) {
    JsonArray result = new JsonArray();
    Set<String> seen = new LinkedHashSet<>();
    if (!request.origin().isBlank() && !request.destination().isBlank()) {
      addSegment(result, seen, request.origin(), request.destination(), "", "");
    }
    for (JsonElement dayElement : days) {
      if (!dayElement.isJsonObject()) continue;
      JsonObject day = dayElement.getAsJsonObject();
      JsonArray activities = array(day, "activities");
      String previous = "";
      for (JsonElement activityElement : activities) {
        if (!activityElement.isJsonObject()) continue;
        JsonObject activity = activityElement.getAsJsonObject();
        String current = text(activity, "location");
        if (current.isBlank()) current = text(activity, "title");
        if (!previous.isBlank() && !current.isBlank()) {
          addSegment(result, seen, previous, current, request.destination(), text(day, "date"));
        }
        if (!current.isBlank()) previous = current;
        if (result.size() >= 16) return result;
      }
    }
    return result;
  }

  private void addSegment(JsonArray result, Set<String> seen, String origin, String destination,
                          String city, String date) {
    String key = origin + "\u001f" + destination + "\u001f" + date;
    if (!seen.add(key)) return;
    JsonObject segment = new JsonObject();
    segment.addProperty("origin", origin); segment.addProperty("destination", destination);
    segment.addProperty("city", city); segment.addProperty("date", date); result.add(segment);
  }

  private JsonArray openingPlaces(TravelRequest request, JsonArray days) {
    JsonArray result = new JsonArray();
    Set<String> seen = new LinkedHashSet<>();
    for (JsonElement dayElement : days) {
      if (!dayElement.isJsonObject()) continue;
      String date = text(dayElement.getAsJsonObject(), "date");
      for (JsonElement activityElement : array(dayElement.getAsJsonObject(), "activities")) {
        if (!activityElement.isJsonObject()) continue;
        JsonObject activity = activityElement.getAsJsonObject();
        String name = text(activity, "location");
        if (name.isBlank()) name = text(activity, "title");
        if (name.isBlank() || !seen.add(name) || result.size() >= 8) continue;
        JsonObject place = new JsonObject(); place.addProperty("name", name);
        place.addProperty("city", request.destination()); place.addProperty("date", date); result.add(place);
      }
    }
    return result;
  }

  private JsonObject objectWith(String name, JsonElement value) {
    JsonObject result = new JsonObject(); result.add(name, value); return result;
  }

  private void addEvidence(JsonArray evidence, String type, JsonObject data) {
    if (data.isEmpty()) return;
    JsonObject row = new JsonObject(); row.addProperty("type", type); row.add("data", data.deepCopy());
    evidence.add(row);
  }

  private void appendValidationRisks(JsonArray risks, JsonObject validationData) {
    JsonObject validation = object(validationData, "validation");
    for (JsonElement element : array(validation, "issues")) {
      if (element.isJsonObject()) risks.add(element.getAsJsonObject().deepCopy());
    }
  }

  private void append(JsonArray target, JsonArray values) {
    for (JsonElement value : values) target.add(value.deepCopy());
  }

  private JsonObject risk(String code, String message) {
    JsonObject result = new JsonObject(); result.addProperty("code", code);
    result.addProperty("message", message); result.addProperty("verificationRequired", true); return result;
  }

  private JsonObject object(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name) : new JsonObject();
  }

  private JsonArray array(JsonObject value, String name) {
    return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray();
  }

  private String text(JsonObject value, String name) {
    return value.has(name) && !value.get(name).isJsonNull() ? value.get(name).getAsString() : "";
  }
}
