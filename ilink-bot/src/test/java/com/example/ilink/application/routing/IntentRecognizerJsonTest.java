package com.example.ilink.application.routing;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class IntentRecognizerJsonTest {

    @Test
    void explicitReminderUsesLocalCalendarRouteWithoutCallingTheModel() {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());

        IntentPlan plan = recognizer.recognize("user", "明天 15 点提醒我该喝水了",
                new IntentContext(false, false, false, false, false));

        assertEquals(1, plan.actions().size());
        assertEquals("calendar_event", plan.actions().getFirst().route().intent());
        assertEquals("create", plan.actions().getFirst().route().calendarAction());
        assertEquals("喝水", plan.actions().getFirst().route().calendarTitle());
        assertEquals("明天 15 点提醒我该喝水了", plan.actions().getFirst().route().calendarTime());
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
}
