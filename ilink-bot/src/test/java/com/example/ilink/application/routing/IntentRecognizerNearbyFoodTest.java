package com.example.ilink.application.routing;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.http.HttpClient;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRecognizerNearbyFoodTest {

    @Test
    void modelActionTextCannotTurnCasualGreetingIntoNearbyFood() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = action("nearby_food", "附近有什么好吃的");

        normalize(recognizer, "你好", "附近有什么好吃的", action);

        assertEquals("chat", action.get("intent").getAsString());
    }

    @Test
    void explicitNearbyDiningRequestKeepsNearbyFoodRoute() throws Exception {
        IntentRecognizer recognizer = new IntentRecognizer(HttpClient.newHttpClient());
        JsonObject action = action("nearby_food", "推荐附近美食");

        normalize(recognizer, "我现在在杭州西湖，推荐附近美食", "推荐附近美食", action);

        assertEquals("nearby_food", action.get("intent").getAsString());
    }

    private static JsonObject action(String intent, String actionText) {
        JsonObject action = new JsonObject();
        action.addProperty("intent", intent);
        action.addProperty("action_text", actionText);
        return action;
    }

    private static void normalize(IntentRecognizer recognizer, String userMessage,
                                  String actionText, JsonObject action) throws Exception {
        Method method = IntentRecognizer.class.getDeclaredMethod(
                "normalizeAction", String.class, String.class, JsonObject.class, IntentContext.class);
        method.setAccessible(true);
        method.invoke(recognizer, userMessage, actionText, action,
                new IntentContext(false, false, false, false, false));
    }
}
