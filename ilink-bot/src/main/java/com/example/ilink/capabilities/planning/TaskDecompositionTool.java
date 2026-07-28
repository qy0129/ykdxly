package com.example.ilink.capabilities.planning;

import com.example.ilink.capabilities.planning.TaskPlanningService;
import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.application.tooling.Tool;
import com.example.ilink.application.tooling.ToolArguments;
import com.example.ilink.application.tooling.ToolContext;
import com.example.ilink.application.tooling.ToolDefinition;
import com.example.ilink.application.tooling.ToolResult;
import com.google.gson.JsonObject;

import java.util.List;

/** Function Calling 任务拆分工具。 */
public final class TaskDecompositionTool implements Tool {

    public static final String NAME = "break_down_task";

    private final TaskPlanningService planningService;
    private final ToolDefinition definition;

    /** 创建任务拆分工具。 */
    public TaskDecompositionTool(TaskPlanningService planningService) {
        this.planningService = planningService;
        JsonObject properties = new JsonObject();
        properties.add("goal", ToolDefinition.stringProperty("需要完成的完整目标"));
        this.definition = new ToolDefinition(
                NAME,
                "任务拆分",
                "将复杂目标拆成有预计耗时和优先级的可执行任务。",
                ToolDefinition.objectParameters(properties, "goal"),
                true);
    }

    /** 返回任务拆分工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 调用规划服务拆分目标。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String goal = ToolArguments.requireString(arguments, "goal");
        List<PlanTask> tasks = planningService.decompose(goal);
        return tasks.isEmpty()
                ? ToolResult.failure("任务拆分失败")
                : ToolResult.success(planningService.formatTasks(tasks), tasks);
    }
}
