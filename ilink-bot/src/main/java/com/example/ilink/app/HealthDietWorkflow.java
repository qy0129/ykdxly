package com.example.ilink.app;

import com.example.ilink.conversation.DietPlanSessionStore;
import com.example.ilink.feature.calendar.CalendarService;
import com.example.ilink.model.CalendarEvent;
import com.example.ilink.routing.IntentResult;
import com.example.ilink.tools.core.ToolContext;
import com.example.ilink.tools.core.ToolManager;
import com.example.ilink.tools.core.ToolResult;
import com.example.ilink.tools.food.FoodDeliveryTool;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.google.gson.JsonObject;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** 生成可执行的饮食建议，并在用户确认后把三餐提醒写入日历。 */
public final class HealthDietWorkflow {

    private final CalendarService calendarService;
    private final DietPlanSessionStore sessions;
    private final ReplySender replySender;
    private final ToolManager toolManager;

    public HealthDietWorkflow(CalendarService calendarService, DietPlanSessionStore sessions, ReplySender replySender,
                               ToolManager toolManager) {
        this.calendarService = calendarService;
        this.sessions = sessions;
        this.replySender = replySender;
        this.toolManager = toolManager;
    }

    public boolean hasPending(String userId) { return sessions.has(userId); }

    /** 新饮食请求使用路由模型给出的目标进入偏好收集流程。 */
    public void handle(ILinkClient client, String userId, IntentResult route) throws Exception {
        String goal = route.dietGoal().isBlank() ? "均衡饮食" : route.dietGoal();
        sessions.set(userId, new DietPlanSessionStore.DietPlanDraft(goal, "preferences"));
        replySender.sendReply(client, userId, "可以。为了按你的情况选外卖，先告诉我两件事：\n"
                + "1. 口味和忌口，例如“微辣、不吃香菜、想吃米饭”。\n"
                + "2. 筛选标准，例如“减脂、600 千卡以内、蛋白质高、预算 30 元”。\n\n"
                + "你可以直接合在一句话里回复。");
    }

    /** 用户后续回答口味、预算或同步确认时无需再次调用路由模型。 */
    public void handlePending(ILinkClient client, String userId, String text) throws Exception {
        DietPlanSessionStore.DietPlanDraft draft = sessions.get(userId);
        if ("preferences".equals(draft.stage())) {
            JsonObject arguments = new JsonObject();
            arguments.addProperty("goal", draft.goal());
            arguments.addProperty("preferences", text);
            ToolResult result = toolManager.execute(FoodDeliveryTool.NAME, new ToolContext(userId), arguments);
            if (!result.success()) {
                sessions.clear(userId);
                replySender.sendReply(client, userId, result.output());
                return;
            }
            sessions.set(userId, draft.withStage("calendar"));
            replySender.sendReply(client, userId, result.output()
                    + "\n\n要把每日三餐提醒写入日历吗？回复“同步”即可。", "text", "default");
            return;
        }
        if (text.contains("不") || text.contains("取消")) {
            sessions.clear(userId);
            replySender.sendReply(client, userId, "好的，这次不写入日历。需要时随时告诉我你的用餐安排。");
            return;
        }
        if (!text.contains("是") && !text.contains("同步") && !text.contains("记录")) {
            replySender.sendReply(client, userId, "回复“同步”即可把早餐、午餐和晚餐提醒写入日历；回复“取消”则不记录。");
            return;
        }
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1).with(LocalTime.of(8, 0));
        calendarService.create(userId, dietTitle("早餐", draft.goal()), "健康", tomorrow, "daily", 0);
        calendarService.create(userId, dietTitle("午餐", draft.goal()), "健康", tomorrow.with(LocalTime.of(12, 30)), "daily", 0);
        CalendarEvent dinner = calendarService.create(userId, dietTitle("晚餐", draft.goal()), "健康",
                tomorrow.with(LocalTime.of(18, 30)), "daily", 0);
        sessions.clear(userId);
        replySender.sendReply(client, userId, "已把三餐安排写入日历：每天 08:00、12:30、18:30 提醒。"
                + "晚餐提醒将在 " + dinner.nextReminderAt().toLocalDate() + " 开始生效。");
    }

    private String dietTitle(String meal, String goal) { return goal + meal + "饮食安排"; }
}
