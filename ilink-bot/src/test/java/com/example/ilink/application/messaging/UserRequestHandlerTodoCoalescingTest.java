package com.example.ilink.application.messaging;

import com.example.ilink.application.routing.IntentAction;
import com.example.ilink.application.routing.IntentPlan;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.application.routing.MessageMode;
import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserRequestHandlerTodoCoalescingTest {

    private static final IntentResult TODO = new Gson().fromJson(
            "{\"intent\":\"todo\",\"todoAction\":\"create\"}", IntentResult.class);
    private static final IntentResult WEATHER = new Gson().fromJson(
            "{\"intent\":\"weather\",\"weather_location\":\"杭州\",\"weather_day\":\"tomorrow\"}",
            IntentResult.class);

    @Test
    void mergesTodoCreationLoopIntoOneBatchAction() {
        IntentPlan plan = new IntentPlan(List.of(
                todo("r1", "今晚 22:00 学习 Python 两小时"),
                todo("r2", "周六早上 11:30 规划下周健身计划"),
                todo("r3", "周日下午 3 点 整理所有学习打卡记录"),
                todo("r4", "每条任务临近前半小时推送提醒"),
                todo("r5", "后续定期检查我完成情况")), MessageMode.COMMAND);

        IntentPlan merged = UserRequestHandler.coalesceDependentActions(plan);

        assertEquals(1, merged.actions().size());
        assertEquals(MessageMode.COMMAND, merged.messageMode());
        String request = merged.actions().getFirst().requestText();
        assertTrue(request.contains("学习 Python 两小时"));
        assertTrue(request.contains("规划下周健身计划"));
        assertTrue(request.contains("整理所有学习打卡记录"));
        assertTrue(request.contains("前半小时推送提醒"));
        assertTrue(request.contains("定期检查我完成情况"));
    }

    @Test
    void preservesTodoControlActionsAndRemapsBatchDependency() {
        IntentAction dependent = new IntentAction("r3", "完成后说明结果", List.of("r2"), IntentResult.chat());
        IntentPlan plan = new IntentPlan(List.of(
                todo("r1", "明天提交周报"),
                todo("r2", "后天给客户打电话"), dependent,
                todo("r4", "查看我的待办")), MessageMode.COMMAND);

        IntentPlan merged = UserRequestHandler.coalesceDependentActions(plan);

        assertEquals(3, merged.actions().size());
        assertEquals(List.of("r1"), merged.actions().get(1).dependsOn());
        assertEquals("查看我的待办", merged.actions().get(2).requestText());
    }

    @Test
    void mergesWeatherAdviceChatIntoWeatherAction() {
        IntentAction weather = new IntentAction(
                "r1", "查询明天杭州的天气", List.of(), WEATHER);
        IntentAction advice = new IntentAction(
                "r2", "判断是否适合去西湖", List.of("r1"), IntentResult.chat());
        IntentAction dependent = new IntentAction(
                "r3", "完成后记录结果", List.of("r2"), IntentResult.chat());
        IntentPlan plan = new IntentPlan(List.of(weather, advice, dependent), MessageMode.COMMAND);

        IntentPlan merged = UserRequestHandler.coalesceDependentActions(plan);

        assertEquals(2, merged.actions().size());
        assertEquals("weather", merged.actions().getFirst().route().intent());
        assertTrue(merged.actions().getFirst().requestText().contains("判断是否适合去西湖"));
        assertEquals(List.of("r1"), merged.actions().get(1).dependsOn());
    }

    @Test
    void queryTextOverridesIncorrectCreateAction() {
        assertEquals("list", UserRequestHandler.resolveTodoAction(
                "create", "查询我刚才创建的待办事项和提醒安排"));
        assertEquals("unknown", UserRequestHandler.resolveTodoAction("", "待办"));
    }

    private IntentAction todo(String id, String text) {
        return new IntentAction(id, text, List.of(), TODO);
    }
}
