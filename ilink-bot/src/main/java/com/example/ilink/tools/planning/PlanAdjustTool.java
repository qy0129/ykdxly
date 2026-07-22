package com.example.ilink.tools.planning;

import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.feature.planning.TaskPlanningService;
import com.example.ilink.model.TaskPlan;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 计划调整工具。 */
public final class PlanAdjustTool implements Tool {

    public static final String NAME = "adjust_task_plan";

    private final TaskPlanningService planningService;
    private final PlanSessionStore planSessions;
    private final ToolDefinition definition;

    /** 创建计划调整工具。 */
    public PlanAdjustTool(TaskPlanningService planningService, PlanSessionStore planSessions) {
        this.planningService = planningService;
        this.planSessions = planSessions;
        JsonObject properties = new JsonObject();
        properties.add("change_request", ToolDefinition.stringProperty("用户对当前计划的调整要求"));
        this.definition = new ToolDefinition(
                NAME,
                "调整任务计划",
                "根据任务完成、延期或时间变化，调整用户当前计划。没有当前计划时不要调用。",
                ToolDefinition.objectParameters(properties, "change_request"),
                true);
    }

    /** 返回计划调整工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 读取当前计划、完成调整并覆盖保存。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        TaskPlan current = planSessions.get(context.userId());
        if (current == null) {
            return ToolResult.failure("当前没有可以调整的计划，请先创建计划。");
        }
        String request = ToolArguments.requireString(arguments, "change_request");
        TaskPlan adjusted = planningService.adjustPlan(current, request);
        planSessions.set(context.userId(), adjusted);
        return ToolResult.success("计划已调整。\n\n" + adjusted.toDisplayText(), adjusted);
    }
}
