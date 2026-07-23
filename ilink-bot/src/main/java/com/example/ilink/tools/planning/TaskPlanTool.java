package com.example.ilink.tools.planning;

import com.example.ilink.feature.planning.TaskPlanningService;
import com.example.ilink.model.PlanTask;
import com.example.ilink.model.TaskPlan;
import com.example.ilink.tools.core.Tool;
import com.example.ilink.tools.core.ToolArguments;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolDefinition;
import com.example.ilink.tools.core.ToolResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.time.LocalDate;
import java.util.List;

/** Function Calling 任务计划生成工具。 */
public final class TaskPlanTool implements Tool {

    public static final String NAME = "create_task_plan";

    private final TaskPlanningService planningService;
    private final Gson gson = new Gson();
    private final ToolDefinition definition;

    /** 创建计划生成工具。 */
    public TaskPlanTool(TaskPlanningService planningService) {
        this.planningService = planningService;
        JsonObject properties = new JsonObject();
        properties.add("goal", ToolDefinition.stringProperty("需要完成的最终目标"));
        properties.add("deadline", ToolDefinition.stringProperty("resolve_date 返回的 yyyy-MM-dd 截止日期"));
        properties.add("available_time", ToolDefinition.stringProperty("用户每天或各时间段的可用时间说明"));
        properties.add("tasks_json", ToolDefinition.stringProperty("break_down_task 返回的任务列表 JSON"));
        this.definition = new ToolDefinition(
                NAME,
                "生成任务计划",
                "根据任务列表、截止日期和可用时间生成按天安排的执行计划。",
                ToolDefinition.objectParameters(properties,
                        "goal", "deadline", "available_time", "tasks_json"),
                true);
    }

    /** 返回任务计划工具定义。 */
    @Override
    public ToolDefinition definition() {
        return definition;
    }

    /** 将任务列表排入截止日期之前的可用时间。 */
    @Override
    public ToolResult execute(ToolContext context, JsonObject arguments) {
        String goal = ToolArguments.requireString(arguments, "goal");
        LocalDate deadline = LocalDate.parse(ToolArguments.requireString(arguments, "deadline"));
        String availableTime = ToolArguments.string(arguments, "available_time", "每天2小时");
        PlanTask[] tasks = gson.fromJson(
                ToolArguments.requireString(arguments, "tasks_json"), PlanTask[].class);
        TaskPlan plan = planningService.createPlan(
                goal, deadline, availableTime, tasks == null ? List.of() : List.of(tasks));
        return ToolResult.success(plan.toDisplayText(), plan);
    }
}
