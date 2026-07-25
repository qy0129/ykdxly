package com.example.ilink.feature.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceTest {

    @Test
    void requiresPolishedBriefingToKeepEveryWeatherFact() {
        String draft = "杭州当前天气：多云\n"
                + "当前温度：31℃，体感温度：35℃\n"
                + "温度：27℃ 至 36℃\n"
                + "降水概率：40%\n"
                + "湿度：68%，风速：13 km/h\n"
                + "数据更新时间：2026-07-24 16:00（当地时间）\n"
                + "来源：Open-Meteo\n"
                + "出行提醒：记得防晒。";

        assertTrue(ChatService.preservesWeatherFacts(draft, "早上好。\n" + draft));
        assertFalse(ChatService.preservesWeatherFacts(draft,
                draft.replace("当前温度：31℃，体感温度：35℃\n", "")));
        assertFalse(ChatService.preservesWeatherFacts(draft,
                draft.replace("降水概率：40%", "降水概率：20%")));
    }
}
