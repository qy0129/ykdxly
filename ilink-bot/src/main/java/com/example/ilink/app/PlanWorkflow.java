package com.example.ilink.app;

import com.example.ilink.conversation.ChatHistoryStore;
import com.example.ilink.conversation.PlanSessionStore;
import com.example.ilink.model.PlanTask;
import com.example.ilink.model.TaskPlan;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.feature.document.DocumentService;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.document.DocumentToolOutput;
import com.example.ilink.tools.document.PlanDocumentTool;
import com.example.ilink.tools.planning.DateTimeTool;
import com.example.ilink.tools.planning.PlanAdjustTool;
import com.example.ilink.tools.planning.PlanProgressTool;
import com.example.ilink.tools.planning.TaskDecompositionTool;
import com.example.ilink.tools.planning.TaskPlanTool;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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
    private final DocumentService documentService;
    private final CalendarService calendarService;
    private final Gson gson = new Gson();

    /** 注入统一工具管理器、计划存储、聊天历史和回复发送器。 */
    public PlanWorkflow(ToolManager toolManager,
                        PlanSessionStore planSessions,
                        ChatHistoryStore chatHistory,
                        ReplySender replySender,
                        DocumentService documentService,
                        CalendarService calendarService) {
        this.toolManager = toolManager;
        this.planSessions = planSessions;
        this.chatHistory = chatHistory;
        this.replySender = replySender;
        this.documentService = documentService;
        this.calendarService = calendarService;
    }

    /** 依次完成日期计算、任务拆分、计划生成和可选的文档、语音输出。 */
    public void createPlan(ILinkClient client, String userId, String userText,
                           IntentResult route) throws Exception {
        String goal = route.planGoal().isBlank() ? userText : route.planGoal();
        // 模型已区分时长和截止时间；时长计划默认在今天完成，不再从原话猜测日期含义。
        if (route.timeBudgetMinutes() > 0) {
            executeCreatePlan(client, userId, userText, goal, "今天", route.timeBudgetMinutes() + "分钟",
                    new PlanOutputOptions(route.replyMode(), route.voiceStyle(), route.outputFileType()));
            return;
        }
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

    /** 判断是否正等待用户确认将刚生成的计划同步到日历。 */
    public boolean hasPendingCalendarSync(String userId) {
        return planSessions.hasPendingCalendarSync(userId);
    }

    public void clearPending(String userId) {
        planSessions.clearPending(userId);
        planSessions.clearPendingCalendarSync(userId);
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
        planSessions.setPendingCalendarSync(userId, plan);
        chatHistory.add(userId, userText, planResult.output());
        sendPlanResult(client, userId, planResult.output()
                + "\n\n要把这些任务同步到日历，并在每天 20:00 提醒吗？回复“同步”或“取消”。", options);
    }

    /** 调用计划调整工具，并返回调整后的完整计划。 */
    public void adjustPlan(ILinkClient client, String userId, String userText,
                           IntentResult route) throws Exception {
        TaskPlan previous = planSessions.get(userId);
        JsonObject arguments = new JsonObject();
        arguments.addProperty("change_request", userText);
        ToolResult result = toolManager.execute(
                PlanAdjustTool.NAME, new ToolContext(userId), arguments);
        if (result.success()) {
            TaskPlan adjusted = result.dataAs(TaskPlan.class);
            if (previous != null && adjusted != null) syncAdjustedCalendar(userId, previous, adjusted);
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

    /** 用户确认后把计划任务变成一次性日历事件，保留原计划作为进度来源。 */
    public void completeCalendarSync(ILinkClient client, String userId, String text) throws Exception {
        TaskPlan plan = planSessions.getPendingCalendarSync(userId);
        if (plan == null) return;
        if (text.contains("取消") || text.contains("不")) {
            planSessions.clearPendingCalendarSync(userId);
            replySender.sendReply(client, userId, "好的，这份计划暂不写入日历。");
            return;
        }
        if (!text.contains("同步") && !text.contains("是") && !text.contains("记录")) {
            replySender.sendReply(client, userId, "回复“同步”即可写入日历；回复“取消”则保留文本计划。");
            return;
        }
        int count = 0;
        int skipped = 0;
        LocalDateTime now = LocalDateTime.now();
        for (PlanTask task : plan.tasks()) {
            try {
                LocalDate date = LocalDate.parse(task.scheduledDate());
                LocalDateTime eventTime = LocalDateTime.of(date, LocalTime.of(20, 0));
                if (!eventTime.isAfter(now)) {
                    skipped++;
                    continue;
                }
                var event = calendarService.create(userId, task.title(), "学习",
                        eventTime, "none", 0, "", plan.id(), "plan");
                planSessions.linkTaskToCalendar(task.id(), event.id());
                count++;
            } catch (Exception ignored) {
                // 未获得明确日期的任务保留在文本计划中，不创建一个错误的日历提醒。
            }
        }
        planSessions.clearPendingCalendarSync(userId);
        String skippedText = skipped == 0 ? "" : "，另有 " + skipped + " 项任务时间已过，未创建提醒";
        replySender.sendReply(client, userId, "已将 " + count + " 项任务同步到日历" + skippedText
                + "。有效任务会在当天晚上 20:00 提醒。");
    }

    private void syncAdjustedCalendar(String userId, TaskPlan previous, TaskPlan adjusted) {
        Map<String, PlanTask> adjustedById = new HashMap<>();
        adjusted.tasks().forEach(task -> adjustedById.put(task.id(), task));
        boolean wasSynced = previous.tasks().stream()
                .anyMatch(task -> !planSessions.calendarEventIdForTask(task.id()).isBlank());
        if (!wasSynced) return;

        for (PlanTask oldTask : previous.tasks()) {
            String eventId = planSessions.calendarEventIdForTask(oldTask.id());
            if (eventId.isBlank()) continue;
            PlanTask task = adjustedById.get(oldTask.id());
            LocalDateTime eventTime = planEventTime(task);
            if (task == null || "completed".equals(task.status()) || eventTime == null
                    || !eventTime.isAfter(LocalDateTime.now())) {
                calendarService.cancel(eventId);
                planSessions.unlinkTaskFromCalendar(oldTask.id());
            } else {
                calendarService.reschedule(eventId, task.title(), eventTime);
            }
        }

        for (PlanTask task : adjusted.tasks()) {
            if (!planSessions.calendarEventIdForTask(task.id()).isBlank() || "completed".equals(task.status())) continue;
            LocalDateTime eventTime = planEventTime(task);
            if (eventTime == null || !eventTime.isAfter(LocalDateTime.now())) continue;
            var event = calendarService.create(userId, task.title(), "学习", eventTime,
                    "none", 0, "", adjusted.id(), "plan");
            planSessions.linkTaskToCalendar(task.id(), event.id());
        }
    }

    private LocalDateTime planEventTime(PlanTask task) {
        if (task == null || task.scheduledDate().isBlank()) return null;
        try {
            return LocalDateTime.of(LocalDate.parse(task.scheduledDate()), LocalTime.of(20, 0));
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** 根据用户要求发送计划文本、文件和语音。 */
    private void sendPlanResult(ILinkClient client, String userId, String planText,
                                PlanOutputOptions options) throws Exception {
        if ("docx".equals(options.outputFileType()) || "pdf".equals(options.outputFileType())) {
            JsonObject documentArguments = new JsonObject();
            documentArguments.addProperty("content", planText);
            documentArguments.addProperty("output_type", options.outputFileType());
            ToolResult documentResult = toolManager.execute(
                    PlanDocumentTool.NAME, new ToolContext(userId), documentArguments);
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
