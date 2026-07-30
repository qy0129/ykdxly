package com.example.ilink.capabilities.radar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PlanTopicExtractorTest {

    @Test
    void extractsSearchableTermsWithoutGenericActionWords() {
        PlanTopicExtractor extractor = new PlanTopicExtractor();

        PlanTopicExtractor.ExtractedPlan result = extractor.extract(List.of(
                "完成 Java Agent 项目", "学习 Spring AI 工具调用", "实现任务状态持久化"));

        assertTrue(result.topics().contains("Java Agent"));
        assertTrue(result.topics().contains("Spring AI"));
        assertTrue(result.topics().contains("任务状态持久化"));
    }

    @Test
    void fingerprintOnlyChangesWhenPlanSignalsChange() {
        PlanTopicExtractor extractor = new PlanTopicExtractor();

        String first = extractor.extract(List.of("完成 Java Agent 项目")).fingerprint();
        String same = extractor.extract(List.of("完成 Java Agent 项目")).fingerprint();
        String changed = extractor.extract(List.of("完成 Spring AI 项目")).fingerprint();

        assertEquals(first, same);
        assertNotEquals(first, changed);
    }

    @Test
    void doesNotTurnSensitivePlanTextIntoSearchTopic() {
        PlanTopicExtractor.ExtractedPlan result = new PlanTopicExtractor().extract(List.of(
                "目标薪资25000，手机号13800138000", "学习 Java Agent"));

        assertTrue(result.topics().contains("Java Agent"));
        assertFalse(result.topics().stream().anyMatch(topic -> topic.contains("薪资") || topic.contains("138")));
    }
}
