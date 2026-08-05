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
        {"type":"object","properties":{"planningInstruction":{"type":"string","minLength":1}},
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
    JsonObject input = new JsonObject();
    input.addProperty("message", instruction);
    input.addProperty("conversationId", context.conversationId().toString());
    JsonObject legacy = commands.command(input, context.identity(), context.channel());
    if (!legacy.has("draft") || !legacy.get("draft").isJsonObject()) {
      throw new IllegalStateException("TRAVEL_DRAFT_NOT_CREATED");
    }
    return AgentResult.fromLegacy(legacy, context.traceId());
  }
}
