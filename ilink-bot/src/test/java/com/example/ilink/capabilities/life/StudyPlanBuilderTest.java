package com.example.ilink.capabilities.life;

import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.web.SearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class StudyPlanBuilderTest {

    @Test
    void shouldBuildOneExecutableTaskPerDayAndKeepSources() {
        StudyPlanDraft draft = new StudyPlanDraft("高等数学", 7, 60,
                "零基础", "掌握极限和导数", "20:00");
        SearchResult source = new SearchResult("公开课", "课程介绍", "example.org", "", "https://example.org/math");

        List<PlanTask> tasks = new StudyPlanBuilder().build(draft, List.of(source));

        assertEquals(7, tasks.size());
        assertTrue(tasks.stream().allMatch(task -> task.estimatedMinutes() == 60));
        assertTrue(tasks.stream().allMatch(task -> task.description().contains("https://example.org/math")));
        assertTrue(tasks.getLast().title().contains("阶段复盘"));
    }
}
