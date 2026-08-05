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
    if (call.arguments().has("travelData") && call.arguments().get("travelData").isJsonObject()) {
      return deterministicDraft(call.arguments(), context);
    }
    JsonObject input = new JsonObject();
    input.addProperty("message", instruction);
    input.addProperty("conversationId", context.conversationId().toString());
    input.addProperty("skipPersistence", true);
    try {
      JsonObject legacy = commands.command(input, context.identity(), context.channel());
      if (legacy.has("draft") && legacy.get("draft").isJsonObject()) {
        return AgentResult.fromLegacy(legacy, context.traceId());
      }
    } catch (Exception modelError) {
      // 旅游结果已经结构化，二次模型解析失败时使用确定性草案，避免整条行程失败。
    }
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
      JsonObject task = new JsonObject();
      task.addProperty("title", text(day, "title", "旅行日程"));
      task.addProperty("description", activitySummary(day));
      task.addProperty("priority", "medium");
      task.addProperty("estimatedMinutes", 120);
      task.addProperty("dueAt", date + "T18:00:00");
      JsonArray schedules = new JsonArray();
      JsonObject schedule = new JsonObject();
      schedule.addProperty("title", text(day, "title", "旅行日程"));
      schedule.addProperty("startAt", date + "T10:00:00");
      schedule.addProperty("durationMinutes", 120);
      schedules.add(schedule);
      task.add("schedules", schedules);
      JsonArray tasks = new JsonArray(); tasks.add(task); stage.add("tasks", tasks);
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
