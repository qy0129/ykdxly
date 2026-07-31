package com.example.ilink.application.routing;

import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentRecognizerJsonTest {

    @Test
    void reminderIsSplitAndAssignedThroughTheSameRoutePipeline() {
        List<String> responses = List.of(
                "{\"requirements\":[{\"id\":\"r1\",\"text\":\"明天15点提醒喝水\",\"depends_on\":[]}]}",
                "{\"actions\":[{\"requirement_id\":\"r1\",\"intent\":\"calendar_event\","
                        + "\"calendar_action\":\"create\",\"calendar_title\":\"喝水\","
                        + "\"calendar_time\":\"明天 15 点\"}]}"
        );
        AtomicInteger index = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> responses.get(index.getAndIncrement()));

        IntentPlan plan = recognizer.recognize("user", "明天 15 点提醒我该喝水了",
                new IntentContext(false, false, false, false, false));

        assertEquals(1, plan.actions().size());
        assertEquals("calendar_event", plan.actions().getFirst().route().intent());
        assertEquals("create", plan.actions().getFirst().route().calendarAction());
        assertEquals("喝水", plan.actions().getFirst().route().calendarTitle());
        assertEquals("明天 15 点", plan.actions().getFirst().route().calendarTime());
        assertEquals(2, index.get());
    }

    @Test
    void extractsAndLenientlyParsesJsonFromModelExplanation() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        Method parser = IntentRecognizer.class.getDeclaredMethod("parseJsonObject", String.class);
        parser.setAccessible(true);

        JsonObject result = (JsonObject) parser.invoke(recognizer,
                "识别结果：{actions:[{intent:'chat',action_text:'你好'}]} 请执行。");

        assertEquals("chat", result.getAsJsonArray("actions").get(0)
                .getAsJsonObject().get("intent").getAsString());
    }

    @Test
    void requestsJsonObjectOutputAndDropsHistoryOnRetry() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        Method builder = IntentRecognizer.class.getDeclaredMethod("buildRequestBody",
                String.class, String.class, IntentContext.class, boolean.class, boolean.class);
        builder.setAccessible(true);

        JsonObject body = (JsonObject) builder.invoke(recognizer, "user", "查天气",
                new IntentContext(false, false, false, false, false), false, true);

        assertEquals("json_object", body.getAsJsonObject("response_format").get("type").getAsString());
        assertEquals(Config.ROUTER_MAX_TOKENS, body.get("max_tokens").getAsInt());
        assertEquals(2, body.getAsJsonArray("messages").size());
        assertFalse(body.getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString().isBlank());
    }

    @Test
    void fallsBackToChatWhenRoutingFails() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        Method fallback = IntentRecognizer.class.getDeclaredMethod("fallbackChatPlan", String.class);
        fallback.setAccessible(true);

        IntentPlan plan = (IntentPlan) fallback.invoke(recognizer, "你好");

        assertEquals(1, plan.actions().size());
        assertEquals("chat", plan.actions().getFirst().route().intent());
        assertEquals("你好", plan.actions().getFirst().requestText());
    }

    @Test
    void fallsBackToImageEditWhenRoutingFails() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("offline");
        });

        IntentPlan plan = recognizer.recognize("user", "将上面生成那只小猫的眼睛上戴一个墨镜",
                new IntentContext(false, true, false, false, false));

        assertEquals(1, plan.actions().size());
        assertEquals("image_action", plan.actions().getFirst().route().intent());
        assertEquals("edit", plan.actions().getFirst().route().imageAction());
        assertEquals("将上面生成那只小猫的眼睛上戴一个墨镜",
                plan.actions().getFirst().route().imagePrompt());
    }

    @Test
    void fallsBackToTodoWhenRoutingTimesOut() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            throw new IllegalStateException("request timed out");
        });
        String request = """
                新建以下待办事项:
                今晚 22:00 学习 Python 两小时
                周六早上 11:30 规划下周健身计划
                周日下午 3 点 整理所有学习打卡记录
                每条任务临近前半小时推送提醒,后续你可以定期检查我完成情况。
                """.trim();

        IntentPlan plan = recognizer.recognize("user", request,
                new IntentContext(false, false, false, false, false));

        assertEquals(MessageMode.COMMAND, plan.messageMode());
        assertEquals(1, plan.actions().size());
        assertEquals("todo", plan.actions().getFirst().route().intent());
        assertEquals(request, plan.actions().getFirst().requestText());
    }

    @Test
    void correctsModelChatResultToExplicitTodo() {
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {
                  "message_mode":"command",
                  "requirements":[{"id":"r1","text":"新建以下待办事项：明天交周报","depends_on":[]}],
                  "actions":[{
                    "requirement_id":"r1",
                    "action_text":"新建以下待办事项：明天交周报",
                    "intent":"chat"
                  }]
                }
                """);

        IntentPlan plan = recognizer.recognize("user", "新建以下待办事项：明天交周报",
                new IntentContext(false, false, false, false, false));

        assertEquals("todo", plan.actions().getFirst().route().intent());
    }

    @Test
    void restoresFullBatchTodoWhenModelMistakesFirstTimedItemForCalendar() {
        String request = "新建以下待办事项：今晚 20:00 学习 Python 两小时；今晚 20:00 规划下周健身计划；"
                + "明天上午 10:00 整理学习打卡记录。每条任务提前半小时提醒，后续每天帮我复盘。";
        IntentRecognizer recognizer = new IntentRecognizer(body -> """
                {
                  "message_mode":"command",
                  "requirements":[{"id":"r1","text":"创建待办","depends_on":[]}],
                  "actions":[{
                    "requirement_id":"r1",
                    "action_text":"今晚 20:00 学习 Python 两小时",
                    "intent":"calendar_event",
                    "calendar_action":"create",
                    "calendar_title":"学习 Python 两小时",
                    "calendar_time":"今晚 20:00"
                  }]
                }
                """);

        IntentPlan plan = recognizer.recognize("user", request,
                new IntentContext(false, false, false, false, false));

        assertEquals(MessageMode.COMMAND, plan.messageMode());
        assertEquals(1, plan.actions().size());
        assertEquals("todo", plan.actions().getFirst().route().intent());
        assertEquals(request, plan.actions().getFirst().requestText());
    }

    @Test
    void correctsModelChatResultToImageEdit() {
        List<String> responses = List.of(
                "{\"requirements\":[{\"id\":\"r1\",\"text\":\"帮我在刚刚那只小猫的图片上再加一个小狗\",\"depends_on\":[]}]}",
                "{\"actions\":[{\"requirement_id\":\"r1\",\"intent\":\"chat\"}]}"
        );
        AtomicInteger index = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> responses.get(index.getAndIncrement()));

        IntentPlan plan = recognizer.recognize("user", "帮我在刚刚那只小猫的图片上再加一个小狗",
                new IntentContext(false, true, false, false, false));

        assertEquals("image_action", plan.actions().getFirst().route().intent());
        assertEquals("edit", plan.actions().getFirst().route().imageAction());
        assertEquals(2, index.get());
    }
}
