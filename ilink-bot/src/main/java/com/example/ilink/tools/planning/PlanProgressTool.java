package com.example.ilink.tools.planning;

import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.feature.planning.TaskPlanningService;
import com.example.ilink.model.TaskPlan;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.JsonObject;

/** Function Calling 计划进度查询工具。 */
public final class PlanProgressTool implements Tool {

    public static final String NAME = "get_plan_progress";

    private final TaskPlanningService planningService;
    private final PlanSessionStore planSessions;
    private final ToolDefinition definition;

    /** 创建计划进度工具。 */
    public PlanProgressTool(TaskPlanningService planningService, PlanSessionStore planSessions) {
        this.planningService = planningService;
        this.planSessions = planSessions;
        this.definition = new ToolDefinition(
                NAME,
                "查询计划进度",
                "查询用户当前计划的完成率和下一项任务。没有当前计划时不要调用。",
                ToolDefinition.objectParameters(new JsonObject()),
                true);
    }

    /** 返回计划进度工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 读取用户当前计划并计算进度。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        TaskPlan plan = planSessions.get(context.userId());
        if (plan == null) {
            return ToolResult.failure("当前没有任务计划，请先告诉我需要规划什么。");
        }
        return ToolResult.success(planningService.buildProgress(plan), plan);
    }
}
