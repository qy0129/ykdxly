package com.changlu.planner.agent.subagents.diet.tools;

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
import java.time.LocalDate;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把饮食方案转换成计划 App 的待确认草案（设计 §7.2）：LOW_RISK_WRITE、必须携带幂等键、绝不确认草案。
 * 写入粒度：Plan（如"一周减脂饮食计划"）→ 阶段 Stage → 每餐 Task；仅当用户明确给出餐次时间时创建 Schedule。
 *
 * <p>优先使用结构化 mealPlan 确定性构造 create_plan actions（dietData 已携带），绕开 AiCommandService 的
 * 二次模型解析——该模型偶发把自然中文指令转成超长 actions 被截断、或直接返回空 actions，导致 DIET_DRAFT_NOT_CREATED。
 */
public final class DietDraftTool implements ToolHandler {
  private static final Logger LOG = LoggerFactory.getLogger(DietDraftTool.class);
  public static final String NAME = "diet.plan.draft";
  private final AiCommandService commands;

  public DietDraftTool(AiCommandService commands) { this.commands = commands; }

  @Override public ToolDefinition definition() {
    JsonObject input = JsonParser.parseString("""
        {"type":"object","properties":{"planningInstruction":{"type":"string","minLength":1},
          "dietData":{"type":"object"}},"required":["planningInstruction"]}
        """).getAsJsonObject();
    JsonObject output = JsonParser.parseString("""
        {"type":"object","properties":{"draft":{"type":"object"}},"required":["draft"]}
        """).getAsJsonObject();
    return new ToolDefinition(NAME, "1.0.0", "将饮食方案转换成计划、阶段、任务和日程待确认草案",
        input, output, Set.of("planning:write"), ToolRiskLevel.LOW_RISK_WRITE, ToolSideEffect.INTERNAL_WRITE,
        true, Duration.ofSeconds(90), RetryPolicy.none());
  }

  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    if (call.idempotencyKey() == null || call.idempotencyKey().isBlank()) {
      throw new IllegalArgumentException("idempotency_key_required");
    }
    String instruction = call.arguments().has("planningInstruction")
        ? call.arguments().get("planningInstruction").getAsString().trim() : "";
    if (instruction.isBlank()) throw new IllegalArgumentException("DIET_PLANNING_INSTRUCTION_REQUIRED");
    // 结构化 dietData 齐全时直接确定性构造草案，不依赖二次模型解析。
    if (call.arguments().has("dietData") && call.arguments().get("dietData").isJsonObject()) {
      return deterministicDraft(instruction, call.arguments().getAsJsonObject("dietData"), context);
    }
    JsonObject input = new JsonObject();
    input.addProperty("message", instruction);
    input.addProperty("conversationId", context.conversationId().toString());
    JsonObject legacy = commands.command(input, context.identity(), context.channel());
    if (!legacy.has("draft") || !legacy.get("draft").isJsonObject()) {
      throw new IllegalStateException("DIET_DRAFT_NOT_CREATED");
    }
    return AgentResult.fromLegacy(legacy, context.traceId());
  }

  /** 从结构化 mealPlan 确定性构造 create_plan 草案：Plan → 7 个阶段（每天）→ 每餐任务（含日程）。 */
  private AgentResult deterministicDraft(String instruction, JsonObject dietData, AgentContext context)
      throws Exception {
    String goal = text(dietData, "goal", "健康饮食");
    JsonArray mealPlan = dietData.has("mealPlan") && dietData.get("mealPlan").isJsonArray()
        ? dietData.getAsJsonArray("mealPlan") : new JsonArray();
    JsonObject scheduleTimes = dietData.has("scheduleTimes") && dietData.get("scheduleTimes").isJsonObject()
        ? dietData.getAsJsonObject("scheduleTimes") : new JsonObject();
    java.util.List<String[]> proposedSchedules = new java.util.ArrayList<>();

    JsonObject fields = new JsonObject();
    fields.addProperty("title", goal + "一周饮食计划");
    fields.addProperty("description", shoppingDescription(dietData));
    fields.addProperty("color", "#D39A24");
    fields.addProperty("reason", "diet_agent");

    JsonArray stages = new JsonArray();
    LocalDate today = LocalDate.now();
    int dayIndex = 0;
    for (JsonElement dayElement : mealPlan) {
      if (!dayElement.isJsonObject()) continue;
      JsonObject day = dayElement.getAsJsonObject();
      dayIndex++;
      String date = text(day, "date", "");
      if (date.isBlank()) date = today.plusDays(dayIndex - 1).toString();

      JsonObject stage = new JsonObject();
      stage.addProperty("title", "第" + dayIndex + "天");
      stage.addProperty("dueDate", date);
      JsonArray tasks = new JsonArray();
      JsonArray meals = day.has("meals") && day.get("meals").isJsonArray()
          ? day.getAsJsonArray("meals") : new JsonArray();
      for (JsonElement mealElement : meals) {
        if (!mealElement.isJsonObject()) continue;
        JsonObject meal = mealElement.getAsJsonObject();
        String type = text(meal, "type", "");
        String label = mealLabel(type);
        String title = label + "「" + text(meal, "title", "营养餐") + "」";

        JsonObject task = new JsonObject();
        task.addProperty("title", title);
        task.addProperty("description", foodSummary(meal));
        task.addProperty("priority", "medium");
        task.addProperty("estimatedMinutes", 40);
        task.addProperty("dueAt", date + "T23:00:00");
        String time = scheduleTimes.has(type) ? scheduleTimes.get(type).getAsString() : "";
        if (!time.isBlank()) {
          JsonArray schedules = new JsonArray();
          JsonObject schedule = new JsonObject();
          schedule.addProperty("title", title);
          schedule.addProperty("startAt", date + "T" + time + ":00");
          schedule.addProperty("durationMinutes", 40);
          schedules.add(schedule);
          task.add("schedules", schedules);
          proposedSchedules.add(new String[] {date + "T" + time + ":00", "40"});
        }
        tasks.add(task);
      }
      stage.add("tasks", tasks);
      stages.add(stage);
    }
    fields.add("stages", stages);

    JsonObject action = new JsonObject();
    action.addProperty("type", "create_plan");
    action.addProperty("summary", "创建" + goal + "一周饮食计划");
    action.add("fields", fields);
    JsonArray actions = new JsonArray(); actions.add(action);

    // 预检拟建日程是否与已有安排冲突：冲突时不生成草案，改为询问用户如何处理。
    java.util.Set<String> conflicts = new java.util.LinkedHashSet<>();
    for (String[] schedule : proposedSchedules) {
      try {
        conflicts.addAll(commands.scheduleConflicts(context.identity(), schedule[0], Integer.parseInt(schedule[1])));
      } catch (Exception error) {
        LOG.warn("[饮食日程冲突预检失败] run={} 原因={}", context.runId(), error.getMessage());
      }
      if (conflicts.size() >= 3) break;
    }
    if (!conflicts.isEmpty()) {
      String list = String.join("、", conflicts);
      JsonObject data = new JsonObject();
      JsonArray questions = new JsonArray();
      questions.add("饮食计划的时间与已有安排冲突（" + list + "），请调整餐次时间，或先处理已有冲突安排。");
      data.add("questions", questions);
      data.addProperty("message", "饮食计划的时间与已有安排冲突，暂不能生成草案。");
      return AgentResult.waitingUser("饮食计划的时间与已有安排冲突（" + list + "）。你可以调整餐次时间，或先处理已有冲突安排后再试。",
          data, context.traceId());
    }

    JsonObject legacy = commands.createStructuredDraft(context.conversationId(), context.identity(),
        context.channel(), instruction, "已生成饮食计划草案，确认后写入计划与每日日程。", actions);
    return AgentResult.fromLegacy(legacy, context.traceId());
  }

  private String shoppingDescription(JsonObject dietData) {
    if (!dietData.has("shoppingList") || !dietData.get("shoppingList").isJsonArray()) return "";
    StringBuilder shopping = new StringBuilder("购物清单：");
    int count = 0;
    for (JsonElement element : dietData.getAsJsonArray("shoppingList")) {
      if (!element.isJsonObject()) continue;
      String item = text(element.getAsJsonObject(), "item", "");
      if (item.isBlank()) continue;
      if (count++ >= 12) { shopping.append("等"); break; }
      shopping.append(item).append('、');
    }
    return shopping.length() > "购物清单：".length() ? shopping.substring(0, shopping.length() - 1) : "";
  }

  private String foodSummary(JsonObject meal) {
    if (!meal.has("foodItems") || !meal.get("foodItems").isJsonArray()) return "";
    StringBuilder summary = new StringBuilder();
    for (JsonElement element : meal.getAsJsonArray("foodItems")) {
      if (element.isJsonPrimitive() && !element.getAsString().isBlank()) {
        if (summary.length() > 0) summary.append('、');
        summary.append(element.getAsString());
      }
    }
    return summary.toString();
  }

  private String mealLabel(String type) {
    return switch (type) {
      case "breakfast" -> "早餐";
      case "lunch" -> "午餐";
      case "dinner" -> "晚餐";
      case "snack" -> "加餐";
      default -> type.isBlank() ? "餐" : type;
    };
  }

  private String text(JsonObject value, String key, String fallback) {
    return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
  }
}
