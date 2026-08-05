package com.changlu.planner.agent.tools;

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
import java.time.Duration;
import java.util.Set;

/** Standard Tool boundary for plan, task, todo and schedule changes. */
public final class PlanningAssistantTool implements ToolHandler {
  public static final String NAME = "planning.assistant";

  private final AiCommandService commands;
  private final ToolDefinition definition = new ToolDefinition(
      NAME, "1.0.0", "查询并生成计划、任务、待办和日程变更草案",
      new JsonObject(), new JsonObject(), Set.of("planning:write"),
      ToolRiskLevel.LOW_RISK_WRITE, ToolSideEffect.INTERNAL_WRITE, true,
      Duration.ofSeconds(120), RetryPolicy.none());

  public PlanningAssistantTool(AiCommandService commands) { this.commands = commands; }

  @Override public ToolDefinition definition() { return definition; }

  @Override public AgentResult execute(ToolCall call, AgentContext context) throws Exception {
    JsonObject input = call.arguments();
    input.addProperty("conversationId", context.conversationId().toString());
    input.addProperty("skipPersistence", true);
    String message = input.has("message") && !input.get("message").isJsonNull()
        ? input.get("message").getAsString().trim() : "";
    if (message.isBlank()) throw new IllegalArgumentException("message_required");
    JsonObject result = commands.command(input, context.identity(), context.channel());
    return AgentResult.fromLegacy(result, context.traceId());
  }
}
