package com.example.ilink.capabilities.life;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflectionInsightServiceTest {

    @Test
    void sendsStructuredFactsAndParsesBoundedInsight() {
        AtomicReference<JsonObject> request = new AtomicReference<>();
        ReflectionInsightService service = new ReflectionInsightService(body -> {
            request.set(body);
            return """
                    {"summary":"今天晚间学习执行稳定。",
                     "highlights":["完成 Python 学习","保持了晚间节奏","第三条","应被截断"],
                     "problems":["整理任务仍未完成"],
                     "patterns":["明确时间的任务执行更稳定"],
                     "suggestions":["把整理任务拆成两个 25 分钟步骤"],
                     "tomorrow_focus":"先整理记录，再继续学习"}
                    """;
        });
        ReflectionInsightService.Facts facts = facts();

        ReflectionInsightService.Insight insight = service.generate("user", facts);

        assertEquals("今天晚间学习执行稳定。", insight.summary());
        assertEquals(3, insight.highlights().size());
        assertEquals("先整理记录，再继续学习", insight.tomorrowFocus());
        String modelInput = request.get().getAsJsonArray("messages").get(1).getAsJsonObject()
                .get("content").getAsString();
        assertTrue(modelInput.contains("学习 Python"));
        assertTrue(modelInput.contains("整理记录"));
        assertTrue(modelInput.contains("recentTrend"));
    }

    @Test
    void rejectsMalformedOrStatisticRewritingOutput() {
        ReflectionInsightService malformed = new ReflectionInsightService(body -> "not json");
        ReflectionInsightService changesFacts = new ReflectionInsightService(body -> """
                {"summary":"今天完成 99 项任务。","highlights":[],"problems":[],
                 "patterns":[],"suggestions":[],"tomorrow_focus":"继续"}
                """);

        assertNull(malformed.generate("user", facts()));
        assertNull(changesFacts.generate("user", facts()));
    }

    private ReflectionInsightService.Facts facts() {
        return new ReflectionInsightService.Facts("2026-07-31", 2, 1, 0, 0, 1,
                List.of(new ReflectionInsightService.Item(
                        "学习 Python", "todo", "2026-07-31 20:00", "completed", "")),
                List.of(new ReflectionInsightService.Item(
                        "整理记录", "todo", "2026-07-31 21:00", "pending", "")),
                List.of(),
                List.of(new ReflectionInsightService.Trend("2026-07-30", 2, 1, 0, 0)),
                List.of());
    }
}
