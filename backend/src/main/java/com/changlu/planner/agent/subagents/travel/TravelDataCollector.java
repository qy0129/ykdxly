package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.travel.tools.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TravelDataCollector {
  private static final Logger LOG = LoggerFactory.getLogger(TravelDataCollector.class);
  /** 天气/景点/搜索三个外部调用用专用虚拟线程执行器并行，避免挂在 ForkJoinPool.commonPool 上被低核服务器串行化。 */
  private static final ExecutorService COLLECT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();
  private final ToolRegistry tools;
  public TravelDataCollector(ToolRegistry tools) { this.tools = tools; }

  public Collected collect(SubagentRequestView request, AgentContext context) throws Exception {
    JsonObject facts = emptyFacts(); JsonArray risks = new JsonArray();
    JsonObject location = execute(LocationContextTool.NAME, request.arguments(), context, risks, "LOCATION_UNAVAILABLE");
    if (location.has("locationContext")) facts.add("locationContext", location.get("locationContext").deepCopy());
    JsonObject enriched = request.arguments().deepCopy();
    JsonObject locationContext = facts.getAsJsonObject("locationContext");
    for (String key : locationContext.keySet()) enriched.add(key, locationContext.get(key).deepCopy());
    CompletableFuture<JsonObject> weather = async(WeatherForecastTool.NAME, enriched, context, risks, "WEATHER_UNAVAILABLE");
    CompletableFuture<JsonObject> attractions = async(AttractionResearchTool.NAME, enriched, context, risks, "ATTRACTIONS_UNAVAILABLE");
    JsonObject searchArguments = new JsonObject(); searchArguments.addProperty("query", request.researchQuery());
    CompletableFuture<JsonObject> sources = async(DestinationResearchTool.NAME, searchArguments, context, risks, "RESEARCH_UNAVAILABLE");
    long remaining = Math.max(1, context.deadline().toEpochMilli() - System.currentTimeMillis());
    try {
      CompletableFuture.allOf(weather, attractions, sources).get(remaining, TimeUnit.MILLISECONDS);
    } catch (ExecutionException error) {
      Throwable cause = error.getCause();
      if (cause instanceof SecurityException security) throw security;
      if (cause instanceof Exception exception) throw exception;
      throw error;
    }
    mergeArray(facts, "weather", weather.join());
    addWeatherCoverageRisk(request.arguments(), facts.getAsJsonArray("weather"), risks);
    mergeArray(facts, "attractions", attractions.join());
    mergeArray(facts, "sources", sources.join()); facts.add("risks", risks);
    return new Collected(facts, risks);
  }

  public JsonArray routes(JsonObject generated, AgentContext context, JsonArray risks) {
    if (!tools.contains(MapRoutingTool.NAME)) return new JsonArray();
    JsonArray requests = new JsonArray();
    if (generated.has("days") && generated.get("days").isJsonArray()) {
      for (var dayElement : generated.getAsJsonArray("days")) {
        if (!dayElement.isJsonObject() || !dayElement.getAsJsonObject().has("activities")) continue;
        JsonArray activities = dayElement.getAsJsonObject().getAsJsonArray("activities");
        for (int index = 1; index < activities.size(); index++) {
          JsonObject previous = activities.get(index - 1).getAsJsonObject(); JsonObject current = activities.get(index).getAsJsonObject();
          if (!hasCoord(previous) || !hasCoord(current)) continue;
          JsonObject route = new JsonObject(); route.addProperty("origin", previous.get("lng").getAsDouble() + "," + previous.get("lat").getAsDouble());
          route.addProperty("destination", current.get("lng").getAsDouble() + "," + current.get("lat").getAsDouble()); route.addProperty("mode", "walking"); requests.add(route);
        }
      }
    }
    if (requests.isEmpty()) return new JsonArray();
    JsonObject args = new JsonObject(); args.add("routes", requests);
    JsonObject data = execute(MapRoutingTool.NAME, args, context, risks, "ROUTING_UNAVAILABLE");
    return data.has("transitMatrix") ? data.getAsJsonArray("transitMatrix") : new JsonArray();
  }

  private boolean hasCoord(JsonObject activity) {
    return activity != null && activity.has("lng") && !activity.get("lng").isJsonNull()
        && activity.has("lat") && !activity.get("lat").isJsonNull();
  }

  private CompletableFuture<JsonObject> async(String name, JsonObject arguments, AgentContext context, JsonArray risks, String risk) {
    return CompletableFuture.supplyAsync(() -> execute(name, arguments, context, risks, risk), COLLECT_EXECUTOR);
  }
  private JsonObject execute(String name, JsonObject arguments, AgentContext context, JsonArray risks, String riskCode) {
    if (!tools.contains(name)) return new JsonObject();
    try { AgentResult result = tools.execute(new ToolCall(context.runId() + ":" + name, null, name, arguments.deepCopy()), context); return result.data(); }
    catch (SecurityException error) { throw error; }
    catch (Exception error) {
      LOG.warn("[旅行外部服务降级] tool={} type={} message={}", name,
          error.getClass().getSimpleName(), error.getMessage());
      synchronized (risks) {
        JsonObject risk = new JsonObject(); risk.addProperty("code", riskCode);
        risk.addProperty("message", userMessage(riskCode)); risk.addProperty("verificationRequired", true); risks.add(risk);
      }
      return new JsonObject();
    }
  }
  static String userMessage(String riskCode) {
    return switch (riskCode) {
      case "LOCATION_UNAVAILABLE" -> "当前位置暂时无法解析，请补充出发城市后核实往返交通。";
      case "WEATHER_UNAVAILABLE" -> "天气服务未配置或暂时不可用，将在服务恢复后或出发前刷新。";
      case "ATTRACTIONS_UNAVAILABLE" -> "景点实时资料暂时不可用，请在出发前核实开放时间和预约要求。";
      case "RESEARCH_UNAVAILABLE" -> "攻略资料搜索暂时不可用，当前方案仅使用已有信息生成。";
      case "ROUTING_UNAVAILABLE" -> "路线服务暂时不可用，景点间交通时间需要出发前核实。";
      default -> "外部旅行数据暂时不可用，请在出发前再次核实。";
    };
  }
  private void mergeArray(JsonObject target, String name, JsonObject source) { target.add(name, source.has(name) && source.get(name).isJsonArray() ? source.getAsJsonArray(name).deepCopy() : new JsonArray()); }
  private void addWeatherCoverageRisk(JsonObject arguments, JsonArray weather, JsonArray risks) {
    try {
      if (!arguments.has("startDate") || !arguments.has("endDate")) return;
      LocalDate start = LocalDate.parse(arguments.get("startDate").getAsString());
      LocalDate end = LocalDate.parse(arguments.get("endDate").getAsString());
      Set<String> covered = new HashSet<>();
      weather.forEach(item -> { if (item.isJsonObject() && item.getAsJsonObject().has("date")) covered.add(item.getAsJsonObject().get("date").getAsString()); });
      int missing = 0, checked = 0;
      for (LocalDate date = start; !date.isAfter(end) && checked < 60; date = date.plusDays(1), checked++) {
        if (!covered.contains(date.toString())) missing++;
      }
      if (missing == 0) return;
      JsonObject risk = new JsonObject(); risk.addProperty("code", "WEATHER_FORECAST_COVERAGE_INCOMPLETE");
      risk.addProperty("message", "有 " + missing + " 天超出当前天气预报覆盖范围，出发前将自动刷新。");
      risk.addProperty("verificationRequired", true); risks.add(risk);
    } catch (RuntimeException ignored) { }
  }
  private JsonObject emptyFacts() { JsonObject facts = new JsonObject(); facts.add("locationContext", new JsonObject()); facts.add("weather", new JsonArray()); facts.add("attractions", new JsonArray()); facts.add("sources", new JsonArray()); return facts; }
  public record Collected(JsonObject facts, JsonArray risks) {}
  public record SubagentRequestView(JsonObject arguments, String researchQuery) {}
}
