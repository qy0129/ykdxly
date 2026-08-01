package com.example.ilink.capabilities.planning;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPlanningServiceTest {

    @Test
    void usesIndependentModelCallToSplitTodosAndGlobalSettings() {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        TodoPlanningService service = new TodoPlanningService(body -> {
            request.set(body);
            return """
                    {
                      "reminder_minutes": 30,
                      "supervision_enabled": true,
                      "supervision_cadence": "定期检查",
                      "items": [
                        {"title":"学习 Python 两小时","time_text":"今晚 20:00"},
                        {"title":"规划下周健身计划","time_text":"周六早上 10:30"},
                        {"title":"整理所有学习打卡记录","time_text":"周日下午"},
                        {"title":"后续定期检查完成情况","time_text":""}
                      ]
                    }
                    """;
        });
        String original = """
                新建以下待办事项：
                今晚 20:00 学习 Python 两小时
                周六早上 10:30 规划下周健身计划
                周日下午 整理所有学习打卡记录
                每条任务临近前半小时推送提醒，后续你可以定期检查我完成情况。
                """;
        String routed = "今晚 20:00 学习 Python 两小时，周六早上 10:30 规划下周健身计划，"
                + "周日下午整理所有学习打卡记录";

        TodoPlan plan = service.plan(original, routed);

        assertTrue(plan.modelGenerated());
        assertTrue(plan.supervisionEnabled());
        assertEquals("", plan.supervisionCadence());
        assertEquals(LocalTime.of(21, 30), plan.supervisionTime());
        assertEquals(30, plan.reminderMinutes());
        assertEquals(3, plan.drafts().size());
        assertEquals("学习 Python 两小时", plan.drafts().get(0).title());
        assertEquals(20, plan.drafts().get(0).dueAt().getHour());
        assertEquals("规划下周健身计划", plan.drafts().get(1).title());
        assertEquals(10, plan.drafts().get(1).dueAt().getHour());
        assertEquals(30, plan.drafts().get(1).dueAt().getMinute());
        assertEquals(14, plan.drafts().get(2).dueAt().getHour());
        assertEquals(2, request.get().getAsJsonArray("messages").size());
        String modelInput = request.get().getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString();
        assertTrue(modelInput.contains("original_message"));
        assertTrue(modelInput.contains("todo_requirements"));
    }

    @Test
    void rejectsInventedAbsoluteDateAndFallsBackToUserText() {
        TodoPlanningService service = new TodoPlanningService(body -> """
                {"reminder_minutes":15,"supervision_enabled":false,"items":[
                  {"title":"提交周报","time_text":"2027-01-01 10:00"}
                ]}
                """);

        TodoPlan plan = service.plan("明天上午 10 点提交周报", "明天上午 10 点提交周报");

        assertFalse(plan.modelGenerated());
        assertEquals(1, plan.drafts().size());
        assertEquals("提交周报", plan.drafts().getFirst().title());
        assertEquals(10, plan.drafts().getFirst().dueAt().getHour());
    }

    @Test
    void fallsBackToLocalParserWhenModelRequestFails() {
        TodoPlanningService service = new TodoPlanningService(body -> {
            throw new IllegalStateException("offline");
        });
        String request = """
                新建以下待办事项：
                今晚 20:00 学习 Python 两小时
                周六早上 10:30 规划下周健身计划
                周日下午 整理所有学习打卡记录
                每条任务临近前半小时推送提醒
                后续你可以定期检查我完成情况
                """;

        TodoPlan plan = service.plan(request);

        assertFalse(plan.modelGenerated());
        assertTrue(plan.supervisionEnabled());
        assertEquals(LocalTime.of(21, 30), plan.supervisionTime());
        assertEquals(30, plan.reminderMinutes());
        assertEquals(3, plan.drafts().size());
    }

    @Test
    void repairsEmptyModelItemsOnceBeforeUsingLocalFallback() {
        AtomicInteger calls = new AtomicInteger();
        TodoPlanningService service = new TodoPlanningService(body -> {
            if (calls.getAndIncrement() == 0) return "{\"items\":[]}";
            assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
            return """
                    {"reminder_minutes":30,"supervision_enabled":false,"items":[
                      {"title":"提交周报","time_text":"明天上午十点"}
                    ]}
                    """;
        });

        TodoPlan plan = service.plan("明天上午十点提交周报", "明天上午十点提交周报");

        assertEquals(2, calls.get());
        assertTrue(plan.modelGenerated());
        assertEquals(1, plan.drafts().size());
        assertEquals("提交周报", plan.drafts().getFirst().title());
    }

    @Test
    void usesOnlyExplicitSupervisionTimeFromOriginalMessage() {
        TodoPlanningService service = new TodoPlanningService(body -> """
                {"reminder_minutes":30,"supervision_enabled":true,
                 "supervision_cadence":"每天晚上九点","items":[
                  {"title":"学习 Python","time_text":"明天上午八点"}
                ]}
                """);

        TodoPlan plan = service.plan(
                "明天上午八点学习 Python，每天晚上十点复盘完成情况",
                "明天上午八点学习 Python");

        assertEquals("每天晚上十点", plan.supervisionCadence());
        assertEquals(LocalTime.of(22, 0), plan.supervisionTime());
    }

    @Test
    void preservesExplicitSupervisionTimeInOfflineFallback() {
        TodoPlanningService service = new TodoPlanningService(body -> {
            throw new IllegalStateException("offline");
        });

        TodoPlan plan = service.plan("明天上午八点学习 Python，每天晚上十点检查完成情况");

        assertFalse(plan.modelGenerated());
        assertTrue(plan.supervisionEnabled());
        assertEquals(1, plan.drafts().size());
        assertEquals("每天晚上十点", plan.supervisionCadence());
        assertEquals(LocalTime.of(22, 0), plan.supervisionTime());
    }

    @Test
    void localEvidenceRecoversSupervisionMissedByModel() {
        TodoPlanningService service = new TodoPlanningService(body -> """
                {"reminder_minutes":30,"supervision_enabled":false,"items":[
                  {"title":"学习 Python","time_text":"明天上午八点"}
                ]}
                """);

        TodoPlan plan = service.plan(
                "明天上午八点学习 Python，后续定期检查我的完成情况",
                "明天上午八点学习 Python");

        assertTrue(plan.supervisionEnabled());
        assertEquals(LocalTime.of(21, 30), plan.supervisionTime());
    }

    @Test
    void explicitNegationDoesNotEnableSupervision() {
        TodoPlanningService service = new TodoPlanningService(body -> """
                {"reminder_minutes":30,"supervision_enabled":true,"items":[
                  {"title":"学习 Python","time_text":"明天上午八点"}
                ]}
                """);

        TodoPlan plan = service.plan(
                "明天上午八点学习 Python，不需要每日复盘",
                "明天上午八点学习 Python");

        assertFalse(plan.supervisionEnabled());
    }
}
