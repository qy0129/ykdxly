package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.contract.AgentContext;
import com.changlu.planner.agent.core.contract.AgentResult;
import com.changlu.planner.agent.core.contract.Subagent;
import com.changlu.planner.agent.core.contract.SubagentDefinition;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.agent.core.tool.ToolCall;
import com.changlu.planner.agent.core.tool.ToolRegistry;
import com.changlu.planner.agent.subagents.diet.tools.DietDraftTool;
import com.changlu.planner.agent.subagents.diet.tools.NutritionReferenceTool;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 领域编排器（设计 §3 核心流程，与 Travel 同构）。只编排 Tool，不直接访问数据库或调用 HTTP：
 * 搜索委托 NutritionReferenceTool，写草案委托 DietDraftTool；营养目标由确定性计算器给出，模型只生成菜单文案。
 */
public final class DietSubagent implements Subagent {
  private static final Logger LOG = LoggerFactory.getLogger(DietSubagent.class);
  private final DietPlannerModel planner;
  private final ToolRegistry tools;
  private final DietPolicy policy;
  private final DietArgumentExtractor extractor;
  private final SubagentDefinition definition;

  public DietSubagent(DietPlannerModel planner, ToolRegistry tools, DietPolicy policy,
                      JsonObject inputSchema, JsonObject outputSchema) {
    this(planner, tools, policy, null, inputSchema, outputSchema);
  }

  public DietSubagent(DietPlannerModel planner, ToolRegistry tools, DietPolicy policy,
                      DietArgumentExtractor extractor,
                      JsonObject inputSchema, JsonObject outputSchema) {
    this.planner = planner;
    this.tools = tools;
    this.policy = policy;
    this.extractor = extractor;
    this.definition = new SubagentDefinition("diet", "1.0.0",
        "把健康饮食需求整理为可执行、可确认的饮食方案，并在用户要求时生成写入计划 App 的待确认草案",
        List.of("健康饮食", "饮食计划", "减脂餐", "减肥餐", "增肌餐", "健身餐", "一周食谱", "每日菜单",
            "控糖饮食", "营养搭配", "食谱推荐", "食物热量", "写饮食计划"),
        List.of("疾病治疗", "诊断建议", "药物", "孕期饮食定制", "儿童饮食定制", "保证减重效果"),
        inputSchema, outputSchema, Set.of(NutritionReferenceTool.NAME, DietDraftTool.NAME),
        true, true, Duration.ofSeconds(420), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    policy.validateInput(request);
    if (policy.unsupportedRequest(request.message())) {
      return AgentResult.failed("DIET_MEDICAL_UNSUPPORTED",
          "健康饮食规划不能替代医疗建议。涉及疾病治疗、诊断或药物使用时，请咨询医生或注册营养师。",
          false, context.traceId());
    }
    // 原文级别的安全拦截：孕妇/哺乳/未成年表述可能在参数提取时丢失，直接按消息文本拒绝。
    if (policy.unsupportedMessageText(request.message())) {
      return AgentResult.failed("DIET_MEDICAL_UNSUPPORTED",
          "为保障安全，本方案不面向未成年人、孕妇或哺乳期人群提供定制饮食计划，请咨询医生或注册营养师。",
          false, context.traceId());
    }
    // Web 入口只传 message、不带结构化 arguments：先用提取器把自然语言补成结构参数，
    // 再按合并后的完整参数做医学筛查与必需字段判定（显式参数优先，提取结果只补缺失项）。
    JsonObject arguments = effectiveArguments(request, context);
    // 提取器是 LLM，偶发漏提取目标或记忆里的资料：用确定性解析兜底补全缺失字段，
    // 避免用户在已提供资料后仍被反复追问"确认目标/年龄/身高/体重/活动量"。
    policy.fillMissingFromContext(arguments, request.message(), context.sharedContext());
    DietRequest dietRequest = DietRequest.from(arguments);
    if (policy.unsupportedProfile(dietRequest)) {
      return AgentResult.failed("DIET_MEDICAL_UNSUPPORTED",
          "为保障安全，本方案不面向未成年人、孕妇或哺乳期人群提供定制饮食计划，请咨询医生或注册营养师。",
          false, context.traceId());
    }
    JsonArray medicalRisks = policy.medicalRiskScreen(dietRequest);

    List<String> missing = policy.requiredFields(dietRequest);
    JsonArray sources = new JsonArray();
    boolean researchUnavailable = false;
    if (missing.isEmpty()) {
      JsonObject researchArguments = new JsonObject();
      researchArguments.addProperty("query", researchQuery(dietRequest));
      try {
        AgentResult research = tools.execute(new ToolCall(context.runId() + ":diet:research", null,
            NutritionReferenceTool.NAME, researchArguments), context);
        if (research.data().has("sources") && research.data().get("sources").isJsonArray()) {
          sources = research.data().getAsJsonArray("sources");
        }
      } catch (SecurityException | IllegalArgumentException error) {
        throw error;
      } catch (Exception error) {
        researchUnavailable = true;
      }
    }

    DietTargetCalculator.TargetCalculation targets =
        missing.isEmpty() ? DietTargetCalculator.calculate(dietRequest) : null;
    JsonObject generated;
    try {
      generated = planner.plan(dietRequest, sources,
          targets == null ? null : targets.dailyTargets(), missing, context.sharedContext());
    } catch (Exception error) {
      // 模型偶发输出非法 JSON 或接口异常：不把原始报错抛给用户，降级为清晰错误。
      LOG.warn("[饮食方案生成失败] run={} 原因={}", context.runId(), error.getMessage());
      return AgentResult.failed("DIET_PLAN_GENERATION_FAILED",
          "饮食方案暂时生成失败，请稍后重试。", false, context.traceId());
    }
    DietResult result = DietResult.fromGenerated(generated, dietRequest, sources, targets, medicalRisks);
    try {
      policy.validate(result);
    } catch (IllegalArgumentException validationError) {
      // 模型输出结构不合格（缺菜单/缺食物明细等）：不抛密文错误，降级为清晰提示。
      LOG.warn("[饮食方案校验失败，降级] run={} 原因={}", context.runId(), validationError.getMessage());
      return AgentResult.failed("DIET_PLAN_INCOMPLETE",
          "饮食方案生成不完整，请重试。", false, context.traceId());
    }
    JsonObject data = result.toData();
    if (researchUnavailable) {
      data.getAsJsonArray("risks").add(DietResult.risk("EXTERNAL_SERVICE_UNAVAILABLE",
          "营养参考资料暂时不可用，菜单热量请以实际份量为准重新估算。"));
    }
    if (!result.questions().isEmpty()) {
      return AgentResult.waitingUser(result.message(), data, context.traceId());
    }
    // 用户消息含"制定/创建/保存"等词时 writeRequested 为真。写入指令不依赖模型的 planningInstruction：
    // 模型偶发输出结构化 DSL（如 CREATE_PLAN(...)）而非自然中文，AiCommandService 的规划代理解析不出 actions
    // 会导致 DIET_DRAFT_NOT_CREATED。改为从结构化 mealPlan 确定性构造自然中文指令，保证草案能被创建。
    boolean wantsSave = policy.writeRequested(request.message(), request.arguments());
    if (!wantsSave) {
      return AgentResult.completed(result.message(), data, context.traceId());
    }
    String planningInstruction = deterministicInstruction(dietRequest, result, request.message());

    JsonObject draftArguments = new JsonObject();
    draftArguments.addProperty("planningInstruction", planningInstruction);
    // 结构化数据随工具调用下发：DietDraftTool 据此确定性构造草案，绕开二次模型解析（见 DietDraftTool）。
    JsonObject dietData = new JsonObject();
    dietData.addProperty("goal", dietRequest.goal());
    dietData.add("mealPlan", result.mealPlan());
    dietData.add("shoppingList", result.shoppingList());
    JsonObject scheduleTimes = parseScheduleTimes(request.message());
    if (scheduleTimes.size() > 0) dietData.add("scheduleTimes", scheduleTimes);
    draftArguments.add("dietData", dietData);
    // 草案写入是增强能力：失败时仍保留完整菜单，但必须明确告知"保存未成功"，不能静默吞掉用户的写入意图。
    AgentResult draft;
    try {
      draft = tools.execute(new ToolCall(context.runId() + ":diet:draft",
          context.runId() + ":diet-plan", DietDraftTool.NAME, draftArguments), context);
    } catch (Exception error) {
      LOG.warn("[饮食草案写入失败，降级返回菜单] run={} 原因={}", context.runId(), error.getMessage());
      data.getAsJsonArray("risks").add(DietResult.risk("DIET_DRAFT_FAILED",
          "菜单已生成，但「保存到我的计划」失败，本次未写入计划；可重新发送请求再试一次保存。"));
      return AgentResult.completed(result.message() + "（已生成菜单，但保存到我的计划失败，未写入。）",
          data, context.traceId());
    }
    JsonObject merged = data.deepCopy();
    for (String key : draft.data().keySet()) merged.add(key, draft.data().get(key).deepCopy());
    return new AgentResult("1.0", draft.status(), draft.message(), merged, draft.errors(), context.traceId(),
        draft.requiresConfirmation(), draft.draftId());
  }

  /**
   * 构造只读搜索词（隐私最小化，设计 §7.1/§8.3）：只使用目标、饮食类型与忌口等主题词，
   * 绝不包含身高、体重、年龄等个人敏感信息，也不回退到原始用户消息。
   */
  static String researchQuery(DietRequest request) {
    List<String> topics = new ArrayList<>();
    String goal = request.goal();
    topics.add(goal.isBlank() ? "均衡饮食" : goal);
    String dietaryType = switch (request.dietaryType()) {
      case "vegetarian" -> "素食";
      case "vegan" -> "纯素";
      case "halal" -> "清真";
      case "pescatarian" -> "鱼素";
      default -> "";
    };
    if (!dietaryType.isBlank()) topics.add(dietaryType);
    int allergyLimit = 0;
    for (var element : request.allergies()) {
      if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) continue;
      if (allergyLimit++ >= 2) break;
      topics.add("不含" + element.getAsString().trim());
    }
    topics.add("一周食谱");
    topics.add("膳食指南");
    return String.join(" ", topics) + " 食材营养";
  }

  /**
   * 计算生效参数：显式传入的 arguments 优先，缺失字段由提取器从 message 补充。
   * 提取失败（异常或空结果）时回退为原始 arguments，不阻断原流程（仍走 requiredFields 追问）。
   */
  private JsonObject effectiveArguments(SubagentRequest request, AgentContext context) {
    JsonObject provided = request.arguments();
    if (extractor == null) return provided;
    JsonObject extracted;
    try {
      extracted = extractor.extract(request.message(), context.sharedContext());
    } catch (Exception error) {
      return provided;
    }
    if (extracted == null || extracted.size() == 0) return provided;
    JsonObject merged = provided.deepCopy();
    for (String key : extracted.keySet()) {
      if (merged.has(key)) continue;
      merged.add(key, extracted.get(key).deepCopy());
    }
    if (extracted.has("profile") && extracted.get("profile").isJsonObject()) {
      JsonObject mergedProfile = merged.has("profile") && merged.get("profile").isJsonObject()
          ? merged.getAsJsonObject("profile") : new JsonObject();
      JsonObject extractedProfile = extracted.getAsJsonObject("profile");
      for (String key : extractedProfile.keySet()) {
        if (!mergedProfile.has(key)) mergedProfile.add(key, extractedProfile.get(key).deepCopy());
      }
      merged.add("profile", mergedProfile);
    }
    // 模型提取的数字字段可能写成字符串（如 age:"25"），归一为数字，避免 validateInput 直接报 INVALID_ARGUMENT 让整个请求失败。
    coerceNumericFields(merged);
    // 提取器是模型，可能幻觉非法枚举/越界数值（如 dietaryType:"paleo"）：删除并视同未提供，
    // 防止非法值进入 DietRequest 后经 toJson 持久化进 taskData.request，在 WAITING_USER 回放时触发 schema 校验崩溃。
    policy.sanitize(merged);
    return merged;
  }

  /**
   * 从结构化 mealPlan / shoppingList / 消息日程时间确定性构造自然中文写入指令。
   * 不依赖模型生成的 planningInstruction（模型偶发输出 CREATE_PLAN(...) 等 DSL，
   * AiCommandService 的规划代理解析不出 actions，导致 DIET_DRAFT_NOT_CREATED）。
   */
  static String deterministicInstruction(DietRequest request, DietResult result, String message) {
    String goal = request.goal().isBlank() ? "健康饮食" : request.goal();
    StringBuilder instruction = new StringBuilder("创建").append(goal)
        .append("一周饮食计划，按天拆分为 7 个阶段，每个阶段包含当天三餐任务和购物/备餐任务：");
    int dayIndex = 0;
    for (JsonElement element : result.mealPlan()) {
      if (!element.isJsonObject()) continue;
      JsonObject day = element.getAsJsonObject();
      dayIndex++;
      String date = day.has("date") && !day.get("date").isJsonNull()
          ? day.get("date").getAsString() : "";
      instruction.append('\n').append("第").append(dayIndex).append("天");
      if (!date.isBlank()) instruction.append("（").append(date).append("）");
      instruction.append('：');
      JsonArray meals = day.has("meals") && day.get("meals").isJsonArray()
          ? day.getAsJsonArray("meals") : new JsonArray();
      for (JsonElement mealElement : meals) {
        if (!mealElement.isJsonObject()) continue;
        JsonObject meal = mealElement.getAsJsonObject();
        String type = meal.has("type") ? meal.get("type").getAsString() : "";
        String label = switch (type) {
          case "breakfast" -> "早餐";
          case "lunch" -> "午餐";
          case "dinner" -> "晚餐";
          case "snack" -> "加餐";
          default -> type.isBlank() ? "餐" : type;
        };
        instruction.append(label).append('「').append(meal.has("title") ? meal.get("title").getAsString() : "营养餐")
            .append('」');
        // 不展开食材明细：规划代理把指令转成 create_plan/create_schedule actions 时会放大内容，
        // 过长会导致响应超 max_tokens 被截断、JSON 解析失败（DIET_DRAFT_NOT_CREATED）。
        instruction.append('；');
      }
    }
    if (dayIndex == 0) {
      // mealPlan 异常为空时退回通用描述，仍保证草案能被创建。
      instruction = new StringBuilder("创建").append(goal)
          .append("一周饮食计划，包含每日三餐、购物清单和简单做法，按阶段拆分为任务并写入我的计划。");
    } else if (!result.shoppingList().isEmpty()) {
      StringBuilder shopping = new StringBuilder("\n购物清单：");
      int count = 0;
      for (JsonElement element : result.shoppingList()) {
        if (!element.isJsonObject()) continue;
        JsonObject item = element.getAsJsonObject();
        String name = item.has("item") ? item.get("item").getAsString() : "";
        if (name.isBlank()) continue;
        if (count++ >= 10) { shopping.append("等"); break; }
        shopping.append(name).append('、');
      }
      instruction.append(shopping);
    }
    instruction.append("\n写入我的计划");
    return withScheduleTimes(instruction.toString(), message);
  }

  /** 用户明确给出具体餐次时间（"早餐 8:00、午餐 12:00、晚餐 18:30"）时，确定性补进写入指令。 */
  static String withScheduleTimes(String instruction, String message) {
    if (instruction == null || instruction.isBlank() || message == null || message.isBlank()) return instruction;
    if (instruction.contains("日程")) return instruction; // 已写排期，幂等跳过
    List<String> meals = new ArrayList<>();
    java.util.regex.Matcher matcher = MEAL_TIME_PATTERN.matcher(message);
    while (matcher.find()) {
      meals.add(matcher.group(1) + "安排在" + matcher.group(2) + ":" + matcher.group(3));
    }
    if (meals.isEmpty()) return instruction;
    return instruction + "；并把每天" + String.join("、", meals) + "，写入日程。";
  }

  /** 餐次时间解析：早餐/午餐/晚餐/加餐 + HH:mm（支持 "早餐 8:00" / "早餐8:00" / "晚餐18:30"）。 */
  private static final java.util.regex.Pattern MEAL_TIME_PATTERN =
      java.util.regex.Pattern.compile("(早餐|午餐|晚餐|加餐|早饭|午饭|晚饭)\\s*[:：]?\\s*(\\d{1,2})[:：](\\d{2})");

  /**
   * 把用户消息里的餐次时间解析成 {餐次类型: "HH:MM"}，供 DietDraftTool 生成每日 Schedule。
   * 小时补零成两位，保证 "2026-08-06T08:00:00" 符合 LocalDateTime 解析。
   */
  static JsonObject parseScheduleTimes(String message) {
    JsonObject times = new JsonObject();
    if (message == null || message.isBlank()) return times;
    java.util.regex.Matcher matcher = MEAL_TIME_PATTERN.matcher(message);
    while (matcher.find()) {
      String type = switch (matcher.group(1)) {
        case "早餐", "早饭" -> "breakfast";
        case "午餐", "午饭" -> "lunch";
        case "晚餐", "晚饭" -> "dinner";
        case "加餐" -> "snack";
        default -> null;
      };
      if (type == null || times.has(type)) continue;
      String hour = matcher.group(2);
      if (hour.length() == 1) hour = "0" + hour;
      times.addProperty(type, hour + ":" + matcher.group(3));
    }
    return times;
  }

  /** 把常见的数字字段从字符串归一为数字（提取器模型偶尔把 age/heightCm 等写成字符串）。 */
  private void coerceNumericFields(JsonObject root) {
    if (root.has("profile") && root.get("profile").isJsonObject()) {
      JsonObject profile = root.getAsJsonObject("profile");
      for (String field : List.of("age", "heightCm", "weightKg", "targetWeightKg")) {
        if (profile.has(field) && profile.get(field).isJsonPrimitive()
            && profile.get(field).getAsJsonPrimitive().isString()) {
          try { profile.addProperty(field, Double.parseDouble(profile.get(field).getAsString().trim())); }
          catch (NumberFormatException ignored) { }
        }
      }
    }
    for (String field : List.of("mealsPerDay", "cookTimeMinutes")) {
      if (root.has(field) && root.get(field).isJsonPrimitive()
          && root.get(field).getAsJsonPrimitive().isString()) {
        try { root.addProperty(field, Integer.parseInt(root.get(field).getAsString().trim())); }
        catch (NumberFormatException ignored) { }
      }
    }
  }
}
