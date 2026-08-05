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
    // Web 入口只传 message、不带结构化 arguments：先用提取器把自然语言补成结构参数，
    // 再按合并后的完整参数做医学筛查与必需字段判定（显式参数优先，提取结果只补缺失项）。
    JsonObject arguments = effectiveArguments(request);
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
    JsonObject generated = planner.plan(dietRequest, sources,
        targets == null ? null : targets.dailyTargets(), missing);
    DietResult result = DietResult.fromGenerated(generated, dietRequest, sources, targets, medicalRisks);
    policy.validate(result);
    JsonObject data = result.toData();
    if (researchUnavailable) {
      data.getAsJsonArray("risks").add(DietResult.risk("EXTERNAL_SERVICE_UNAVAILABLE",
          "营养参考资料暂时不可用，菜单热量请以实际份量为准重新估算。"));
    }
    if (!result.questions().isEmpty()) {
      return AgentResult.waitingUser(result.message(), data, context.traceId());
    }
    // 用户消息含"制定/创建/保存"等词时 writeRequested 为真，但模型可能只在明确"保存/写入"时才生成
    // planningInstruction。指令为空时不应调用草案工具（否则抛 DIET_PLANNING_INSTRUCTION_REQUIRED），
    // 降级为直接返回完整方案。
    if (!policy.writeRequested(request.message(), request.arguments())
        || result.planningInstruction().isBlank()) {
      return AgentResult.completed(result.message(), data, context.traceId());
    }

    JsonObject draftArguments = new JsonObject();
    draftArguments.addProperty("planningInstruction", result.planningInstruction());
    // 草案写入是增强能力：即使失败，也不应吞掉已成功生成的完整菜单。降级返回菜单，草案留待下次。
    AgentResult draft;
    try {
      draft = tools.execute(new ToolCall(context.runId() + ":diet:draft",
          context.runId() + ":diet-plan", DietDraftTool.NAME, draftArguments), context);
    } catch (Exception error) {
      LOG.warn("[饮食草案写入失败，降级返回菜单] run={} 原因={}", context.runId(), error.getMessage());
      return AgentResult.completed(result.message(), data, context.traceId());
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
  private JsonObject effectiveArguments(SubagentRequest request) {
    JsonObject provided = request.arguments();
    if (extractor == null) return provided;
    JsonObject extracted;
    try {
      extracted = extractor.extract(request.message());
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
    return merged;
  }
}
