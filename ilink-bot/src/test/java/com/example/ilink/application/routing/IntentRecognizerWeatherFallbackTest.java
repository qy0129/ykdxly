package com.example.ilink.application.routing;

import com.example.ilink.platform.persistence.MySqlStore;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

class IntentRecognizerWeatherFallbackTest {

    @Test
    void sendsCompleteRoutingContextToRequirementSplitter() {
        AtomicReference<String> firstSystemPrompt = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        IntentRecognizer recognizer = new IntentRecognizer(body -> {
            firstSystemPrompt.compareAndSet(null, body.getAsJsonArray("messages").get(0)
                    .getAsJsonObject().get("content").getAsString());
            if (calls.getAndIncrement() == 0) {
                return "{\"requirements\":[{\"id\":\"r1\",\"text\":\"查天气\",\"depends_on\":[]}]}";
            }
            return "{\"actions\":[{\"requirement_id\":\"r1\",\"intent\":\"weather\"}]}";
        });
        RoutingContext context = new RoutingContext("简洁助手", "用户喜欢步行", "上一轮在规划出行",
                List.of(new MySqlStore.ChatEntry("user", "上一条消息")), "西湖", "杭州",
                ZonedDateTime.of(2026, 7, 29, 9, 0, 0, 0, ZoneId.of("Asia/Shanghai")),
                new IntentContext(false, false, false, false, false), Map.of("taxi", true));

        recognizer.recognize("user", "查天气", context);

        String prompt = firstSystemPrompt.get();
        assertTrue(prompt.contains("简洁助手"));
        assertTrue(prompt.contains("用户喜欢步行"));
        assertTrue(prompt.contains("上一轮在规划出行"));
        assertTrue(prompt.contains("西湖"));
        assertTrue(prompt.contains("Asia/Shanghai"));
        assertTrue(prompt.contains("taxi"));
    }
}
