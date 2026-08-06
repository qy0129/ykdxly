package com.changlu.planner.agent.subagents.travel.tools;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.tool.RetryPolicy;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolDefinition;
import com.changlu.planner.agent.core.tool.ToolHandler;
import com.changlu.planner.agent.core.tool.ToolRiskLevel;
import com.changlu.planner.agent.core.tool.ToolSideEffect;
import com.changlu.planner.features.command.AiCommandService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.Set;

/** Converts a reviewed travel plan into the existing confirmation draft; it never confirms the draft. */
public final class TravelDraftTool implements ToolHandler {
  public static final String NAME = "travel.plan.draft";
  private final AiCommandService commands;

  public TravelDraftTool(AiCommandService commands) { this.commands = commands; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"planningInstruction":{"type":"string","minLength":1},"travelData":{"type":"object"}},
        "required":["planningInstruction"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString("""
        {"type":"object","properties":{"draft":{"type":"object"}},"required":["draft"]}
        """).getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "将旅行方案转换成计划、阶段、任务和日程待确认草案",
        input, output, Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE, ToolSideEffect.INTERNAL_WRITE,
        true, Duration.ofSeconds(90), RetryPolicy.none());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) {
      throw new IllegalArgumentException("idempotency_key_required");
    }
    String instruction = call.arguments().has("planningInstruction")
        ? call.arguments().get("planningInstruction").getAsString().trim() : "";
    if (instruction.isBlank()) throw new IllegalArgumentException("TRAVEL_PLANNING_INSTRUCTION_REQUIRED");
    if (!call.arguments().has("travelData") || !call.arguments().get("travelData").isJsonObject())
      throw new IllegalArgumentException("TRAVEL_DATA_REQUIRED");
    return deterministicDraft(call.arguments(), context);
  }

  private AgentResult deterministicDraft(JsonObject arguments, AgentContext context) throws Exception {
    JsonObject travel = arguments.has("travelData") && arguments.get("travelData").isJsonObject()
        ? arguments.getAsJsonObject("travelData") : new JsonObject();
    JsonObject request = travel.has("request") && travel.get("request").isJsonObject()
        ? travel.getAsJsonObject("request") : new JsonObject();
    String destination = text(request, "destination", "旅行目的地");
    String endDate = text(request, "endDate", "");
    JsonObject fields = new JsonObject();
    fields.addProperty("title", destination + "旅行计划");
    fields.addProperty("description", "由旅游 Agent 根据逐日行程生成，确认后写入计划和日历。");
    fields.addProperty("color", "#D39A24");
    fields.addProperty("reason", "travel_agent");
    if (!endDate.isBlank()) fields.addProperty("dueDate", endDate);
    JsonArray stages = new JsonArray();
    appendPreparationStage(stages, travel);
    JsonArray days = travel.has("days") && travel.get("days").isJsonArray()
        ? travel.getAsJsonArray("days") : new JsonArray();
    for (JsonElement dayElement : days) {
      if (!dayElement.isJsonObject()) continue;
      JsonObject day = dayElement.getAsJsonObject();
      String date = text(day, "date", "");
      if (date.isBlank()) continue;
      JsonObject stage = new JsonObject();
      stage.addProperty("title", text(day, "title", "旅行日程"));
      stage.addProperty("dueDate", date);
      JsonArray tasks = new JsonArray(); JsonArray activities = day.has("activities") && day.get("activities").isJsonArray()
          ? day.getAsJsonArray("activities") : new JsonArray(); int fallbackMinutes = 9 * 60 + 30;
      for (JsonElement activityElement : activities) {
        if (!activityElement.isJsonObject()) continue; JsonObject activity = activityElement.getAsJsonObject();
        int duration = number(activity, "durationMinutes", 90); String startTime = text(activity, "startTime", "");
        boolean estimatedTime = startTime.isBlank(); if (estimatedTime) startTime = "%02d:%02d".formatted(fallbackMinutes / 60, fallbackMinutes % 60);
        fallbackMinutes += duration + 45;
        JsonObject task = new JsonObject(); String title = text(activity, "title", text(activity, "attractionName", "旅行活动"));
        task.addProperty("title", title); task.addProperty("description", activityDescription(activity, estimatedTime));
        task.addProperty("priority", Boolean.TRUE.equals(nullableBoolean(activity, "requiresReservation")) ? "high" : "medium");
        task.addProperty("estimatedMinutes", duration); task.addProperty("dueAt", date + "T" + startTime + ":00");
        JsonObject schedule = new JsonObject(); schedule.addProperty("title", title); schedule.addProperty("description", activityDescription(activity, estimatedTime));
        schedule.addProperty("startAt", date + "T" + startTime + ":00"); schedule.addProperty("durationMinutes", duration);
        schedule.addProperty("locationName", text(activity, "location", text(activity, "attractionName", "")));
        copy(activity, schedule, "lat", "latitude"); copy(activity, schedule, "lng", "longitude"); copy(activity, schedule, "coordinateSystem", "coordinateSystem");
        schedule.addProperty("timezoneId", timezone(travel)); copy(activity, schedule, "sourceUrl", "sourceUrl"); copy(activity, schedule, "requiresReservation", "reservationRequired");
        JsonArray schedules = new JsonArray(); schedules.add(schedule); task.add("schedules", schedules); tasks.add(task);
      }
      if (tasks.isEmpty()) { JsonObject task = new JsonObject(); task.addProperty("title", text(day, "title", "旅行日程")); task.addProperty("description", activitySummary(day)); task.addProperty("priority", "medium"); task.addProperty("estimatedMinutes", 60); task.addProperty("dueAt", date + "T18:00:00"); tasks.add(task); }
      stage.add("tasks", tasks);
      stages.add(stage);
    }
    fields.add("stages", stages);
    JsonObject action = new JsonObject();
    action.addProperty("type", "create_plan");
    action.addProperty("summary", "创建旅行计划：" + destination);
    action.add("fields", fields);
    JsonArray actions = new JsonArray(); actions.add(action);
    JsonObject legacy = commands.createStructuredDraft(context.conversationId(), context.identity(), context.channel(),
        text(arguments, "planningInstruction", destination + "旅行计划"), "已生成旅行计划和日历待确认草案。", actions);
    return AgentResult.fromLegacy(legacy, context.traceId());
  }

  private void appendPreparationStage(JsonArray stages, JsonObject travel) {
    if (!travel.has("preparationTasks") || !travel.get("preparationTasks").isJsonArray()) return;
    JsonArray source = travel.getAsJsonArray("preparationTasks");
    if (source.isEmpty()) return;
    JsonObject stage = new JsonObject();
    stage.addProperty("stageType", "preparation");
    stage.addProperty("title", "出发前准备");
    String startDate = text(travel.has("request") && travel.get("request").isJsonObject()
        ? travel.getAsJsonObject("request") : new JsonObject(), "startDate", "");
    if (!startDate.isBlank()) stage.addProperty("dueDate", startDate);
    JsonArray tasks = new JsonArray();
    for (JsonElement item : source) {
      if (!item.isJsonObject()) continue;
      JsonObject sourceTask = item.getAsJsonObject();
      String title = text(sourceTask, "title", "旅行准备事项");
      JsonObject task = new JsonObject();
      task.addProperty("title", title);
      task.addProperty("description", text(sourceTask, "description", "出发前完成该准备事项"));
      task.addProperty("priority", text(sourceTask, "priority", "medium"));
      task.addProperty("estimatedMinutes", number(sourceTask, "estimatedMinutes", 30));
      if (!startDate.isBlank()) task.addProperty("dueAt", startDate + "T09:00:00");
      tasks.add(task);
    }
    if (!tasks.isEmpty()) {
      stage.add("tasks", tasks);
      stages.add(stage);
    }
  }

  private int number(JsonObject value, String key, int fallback) {
    return value.has(key) && value.get(key).isJsonPrimitive() && value.getAsJsonPrimitive(key).isNumber()
        ? Math.max(1, value.get(key).getAsInt()) : fallback;
  }

  /** 活动任务描述尽可能还原卡片内容：备注/估算/预约/路上时间/开放时间/地点/备用。 */
  private String activityDescription(JsonObject activity, boolean estimatedTime) {
    StringBuilder value = new StringBuilder(text(activity, "notes", ""));
    if (estimatedTime) appendPart(value, "开始时间为估算");
    Boolean reservation = nullableBoolean(activity, "requiresReservation");
    if (Boolean.TRUE.equals(reservation)) appendPart(value, "需提前预约");
    if (activity.has("transitFromPrevious") && activity.get("transitFromPrevious").isJsonObject()) {
      JsonObject transit = activity.getAsJsonObject("transitFromPrevious");
      Integer transitMinutes = null;
      if (transit.has("durationMinutes") && !transit.get("durationMinutes").isJsonNull() && transit.get("durationMinutes").isJsonPrimitive() && transit.get("durationMinutes").getAsJsonPrimitive().isNumber())
        transitMinutes = transit.get("durationMinutes").getAsInt();
      else if (transit.has("durationSeconds") && !transit.get("durationSeconds").isJsonNull() && transit.get("durationSeconds").isJsonPrimitive() && transit.get("durationSeconds").getAsJsonPrimitive().isNumber())
        transitMinutes = (int) Math.round(transit.get("durationSeconds").getAsDouble() / 60);
      if (transitMinutes != null) appendPart(value, "到达上一站路上约 " + transitMinutes + " 分钟");
    }
    if (activity.has("openingHours") && !activity.get("openingHours").isJsonNull() && !activity.get("openingHours").getAsString().isBlank())
      appendPart(value, "开放时间：" + activity.get("openingHours").getAsString());
    if (activity.has("location") && !activity.get("location").isJsonNull() && !activity.get("location").getAsString().isBlank())
      appendPart(value, "地点：" + activity.get("location").getAsString());
    if (activity.has("backupActivity") && activity.get("backupActivity").isJsonObject() && !activity.getAsJsonObject("backupActivity").keySet().isEmpty())
      appendPart(value, "备用：" + backupName(activity.getAsJsonObject("backupActivity")));
    return value.toString();
  }

  private void appendPart(StringBuilder value, String part) {
    if (value.length() == 0) value.append(part); else value.append("；").append(part);
  }

  private String backupName(JsonObject backup) {
    if (backup.has("attractionName") && !backup.get("attractionName").isJsonNull() && !backup.get("attractionName").getAsString().isBlank())
      return backup.get("attractionName").getAsString();
    if (backup.has("title") && !backup.get("title").isJsonNull() && !backup.get("title").getAsString().isBlank())
      return backup.get("title").getAsString();
    return backup.toString();
  }
  private Boolean nullableBoolean(JsonObject value, String key) { try { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsBoolean() : null; } catch (Exception e) { return null; } }
  private void copy(JsonObject source, JsonObject target, String sourceKey, String targetKey) { if (source.has(sourceKey) && !source.get(sourceKey).isJsonNull()) target.add(targetKey, source.get(sourceKey).deepCopy()); }
  private String timezone(JsonObject travel) { return travel.has("locationContext") && travel.get("locationContext").isJsonObject() ? text(travel.getAsJsonObject("locationContext"), "timezone", "Asia/Shanghai") : "Asia/Shanghai"; }

  private String activitySummary(JsonObject day) {
    if (!day.has("activities") || !day.get("activities").isJsonArray()) return "按当天行程安排，保留机动休息时间。";
    StringBuilder summary = new StringBuilder();
    for (JsonElement item : day.getAsJsonArray("activities")) {
      if (!item.isJsonObject()) continue;
      String title = text(item.getAsJsonObject(), "title", "");
      if (!title.isBlank()) { if (summary.length() > 0) summary.append("；"); summary.append(title); }
    }
    return summary.length() == 0 ? "按当天行程安排，保留机动休息时间。" : summary.toString();
  }

  private String text(JsonObject value, String key, String fallback) {
    return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
  }
}
