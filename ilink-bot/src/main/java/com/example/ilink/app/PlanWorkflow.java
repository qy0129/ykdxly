package com.example.ilink.app;

import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.model.PlanTask;
import com.example.ilink.model.TaskPlan;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.document.DocumentGenerateTool;
import com.example.ilink.tools.document.DocumentToolOutput;
import com.example.ilink.tools.planning.DateTimeTool;
import com.example.ilink.tools.planning.PlanAdjustTool;
import com.example.ilink.tools.planning.PlanProgressTool;
import com.example.ilink.tools.planning.TaskDecompositionTool;
import com.example.ilink.tools.planning.TaskPlanTool;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * 规划请求的多工具工作流。
 *
 * <p>本类只负责调用顺序和结果传递，不实现日期计算或任务规划算法。每一步都通过
 * {@link ToolManager} 执行，因此控制台会完整显示多工具调用过程。</p>
 */
public final class PlanWorkflow {

    private final ToolManager toolManager;
    private final PlanSessionStore planSessions;
    private final ChatHistoryStore chatHistory;
    private final ReplySender replySender;
    private final Gson gson = new Gson();

    /** 注入统一工具管理器、计划存储、聊天历史和回复发送器。 */
    public PlanWorkflow(ToolManager toolManager,
                        PlanSessionStore planSessions,
                        ChatHistoryStore chatHistory,
                        ReplySender replySender) {
        this.toolManager = toolManager;
        this.planSessions = planSessions;
        this.chatHistory = chatHistory;
        this.replySender = replySender;
    }

    /** 依次完成日期计算、任务拆分、计划生成和可选的文档、语音输出。 */
    public void createPlan(ILinkClient client, String userId, String userText,
                           IntentResult route) throws Exception {
        String goal = route.planGoal().isBlank() ? userText : route.planGoal();
        if (route.planDeadline().isBlank()) {
            planSessions.setPending(userId, new PlanSessionStore.PendingPlanRequest(
                    goal,
                    route.planAvailableTime(),
                    route.replyMode(),
                    route.voiceStyle(),
                    route.outputFileType()));
            replySender.sendReply(client, userId,
                    "请补充计划的截止时间，例如“后天”或“3天后”。",
                    route.replyMode(), route.voiceStyle());
            return;
        }

        executeCreatePlan(client, userId, userText, goal,
                route.planDeadline(), route.planAvailableTime(),
                new PlanOutputOptions(route.replyMode(), route.voiceStyle(), route.outputFileType()));
    }

    /** 判断用户是否正在等待补充计划截止时间。 */
    public boolean hasPendingPlan(String userId) {
        return planSessions.hasPending(userId);
    }

    /** 使用用户新回复的截止时间继续完成上一次规划请求。 */
    public void completePendingPlan(ILinkClient client, String userId,
                                    String deadlineText) throws Exception {
        if ("取消".equals(deadlineText.trim())) {
            planSessions.clearPending(userId);
            replySender.sendReply(client, userId, "已取消本次任务规划。");
            return;
        }
        PlanSessionStore.PendingPlanRequest pending = planSessions.getPending(userId);
        if (pending == null) return;
        planSessions.clearPending(userId);
        executeCreatePlan(client, userId, deadlineText, pending.goal(), deadlineText,
                pending.availableTime(), new PlanOutputOptions(
                        pending.replyMode(), pending.voiceStyle(), pending.outputFileType()));
    }

    /** 执行完整的计划创建工具链。 */
    private void executeCreatePlan(ILinkClient client, String userId, String userText,
                                   String goal, String deadlineExpression,
                                   String availableTime, PlanOutputOptions options) throws Exception {

        ToolContext context = new ToolContext(userId);

        JsonObject dateArguments = new JsonObject();
        dateArguments.addProperty("date_expression", deadlineExpression);
        ToolResult dateResult = toolManager.execute(DateTimeTool.NAME, context, dateArguments);
        if (!dateResult.success()) {
            replySender.sendReply(client, userId, dateResult.output(),
                    options.replyMode(), options.voiceStyle());
            return;
        }
        DateTimeTool.DateResult date = dateResult.dataAs(DateTimeTool.DateResult.class);

        JsonObject decompositionArguments = new JsonObject();
        decompositionArguments.addProperty("goal", goal);
        ToolResult decompositionResult = toolManager.execute(
                TaskDecompositionTool.NAME, context, decompositionArguments);
        if (!decompositionResult.success()) {
            replySender.sendReply(client, userId, decompositionResult.output(),
                    options.replyMode(), options.voiceStyle());
            return;
        }

        @SuppressWarnings("unchecked")
        List<PlanTask> tasks = (List<PlanTask>) decompositionResult.data();
        JsonObject planArguments = new JsonObject();
        planArguments.addProperty("goal", goal);
        planArguments.addProperty("deadline", date.resolvedDate());
        planArguments.addProperty("available_time",
                availableTime == null || availableTime.isBlank() ? "每天2小时" : availableTime);
        planArguments.addProperty("tasks_json", gson.toJson(tasks));
        ToolResult planResult = toolManager.execute(TaskPlanTool.NAME, context, planArguments);
        if (!planResult.success()) {
            replySender.sendReply(client, userId, planResult.output(),
                    options.replyMode(), options.voiceStyle());
            return;
        }

        TaskPlan plan = planResult.dataAs(TaskPlan.class);
        planSessions.set(userId, plan);
        chatHistory.add(userId, userText, planResult.output());
        sendPlanResult(client, userId, planResult.output(), options);
    }

    /** 调用计划调整工具，并返回调整后的完整计划。 */
    public void adjustPlan(ILinkClient client, String userId, String userText,
                           IntentResult route) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("change_request", userText);
        ToolResult result = toolManager.execute(
                PlanAdjustTool.NAME, new ToolContext(userId), arguments);
        if (result.success()) {
            chatHistory.add(userId, userText, result.output());
        }
        sendPlanResult(client, userId, result.output(),
                new PlanOutputOptions(route.replyMode(), route.voiceStyle(), route.outputFileType()));
    }

    /** 查询并回复当前计划进度。 */
    public void queryProgress(ILinkClient client, String userId, String userText,
                              IntentResult route) throws Exception {
        ToolResult result = toolManager.execute(
                PlanProgressTool.NAME, new ToolContext(userId), new JsonObject());
        if (result.success()) {
            chatHistory.add(userId, userText, result.output());
        }
        replySender.applyReplyMode(userId, route.replyMode());
        replySender.sendReply(client, userId, result.output(),
                route.replyMode(), route.voiceStyle());
    }

    /** 根据用户要求发送计划文本、文件和语音。 */
    private void sendPlanResult(ILinkClient client, String userId, String planText,
                                PlanOutputOptions options) throws Exception {
        if ("docx".equals(options.outputFileType()) || "pdf".equals(options.outputFileType())) {
            JsonObject documentArguments = new JsonObject();
            documentArguments.addProperty("request",
                    "请将以下任务计划整理为结构清晰的正式计划文档，不要遗漏任务、日期和预计耗时：\n\n"
                            + planText);
            documentArguments.addProperty("output_type", options.outputFileType());
            ToolResult documentResult = toolManager.execute(
                    DocumentGenerateTool.NAME, new ToolContext(userId), documentArguments);
            if (documentResult.success()) {
                DocumentToolOutput output = documentResult.dataAs(DocumentToolOutput.class);
                client.sendFile(userId, output.bytes(), output.fileName(), "任务计划文件");
            }
        }

        replySender.applyReplyMode(userId, options.replyMode());
        replySender.sendReply(client, userId, planText,
                options.replyMode(), options.voiceStyle());
    }

    /** 计划输出时需要保留的文件和语音设置。 */
    private record PlanOutputOptions(
            String replyMode,
            String voiceStyle,
            String outputFileType) {
    }
}
