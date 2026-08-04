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
        true, true, Duration.ofSeconds(120), 3);
  }

  @Override public SubagentDefinition definition() { return definition; }

  @Override public AgentResult execute(SubagentRequest request, AgentContext context) throws Exception {
    policy.validateInput(request);
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

    TravelResult travel = TravelResult.fromGenerated(planner.plan(request, sources), sources);
    policy.validate(travel);
    JsonObject data = travel.toData();
    if (researchUnavailable) {
      JsonObject risk = new JsonObject();
      risk.addProperty("code", "EXTERNAL_SERVICE_UNAVAILABLE");
      risk.addProperty("message", "公开资料暂时不可用，价格、开放时间和交通信息需要再次核实。");
      risk.addProperty("verificationRequired", true);
      data.getAsJsonArray("risks").add(risk);
    }
    if (!travel.questions().isEmpty()) {
      return AgentResult.waitingUser(travel.message(), data, context.traceId());
    }
    if (!policy.writeRequested(request.message(), request.arguments())) {
      return AgentResult.completed(travel.message(), data, context.traceId());
    }

    JsonObject draftArguments = new JsonObject();
    draftArguments.addProperty("planningInstruction", travel.planningInstruction());
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
}
