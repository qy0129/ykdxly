package com.example.ilink.capabilities.planning;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TaskPlanningServiceTest {

    private final TaskPlanningService service = new TaskPlanningService(HttpClient.newHttpClient());

    @Test
    void shouldCreateGloballyUniqueTaskIdsForEachPlan() {
        List<PlanTask> source = List.of(new PlanTask("TASK-1", "学习概念", "", 60,
                "high", "", "pending"));

        TaskPlan first = service.createPlan("高数", LocalDate.now().plusDays(2), "每天1小时", source);
        TaskPlan second = service.createPlan("英语", LocalDate.now().plusDays(2), "每天1小时", source);

        assertNotEquals(first.id(), second.id());
        assertNotEquals(first.tasks().getFirst().id(), second.tasks().getFirst().id());
        assertTrue(first.tasks().getFirst().id().startsWith(first.id()));
    }

    @Test
    void shouldRejectPlanWhenCapacityIsInsufficient() {
        List<PlanTask> source = List.of(new PlanTask("TASK-1", "完整课程", "", 180,
                "high", "", "pending"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createPlan("一天完成课程", LocalDate.now(), "每天1小时", source));

        assertTrue(error.getMessage().contains("可用时间不足"));
    }

    @Test
    void shouldSplitTaskThatExceedsDailyCapacity() {
        List<PlanTask> source = List.of(new PlanTask("TASK-1", "完成综合练习", "", 180,
                "high", "", "pending"));

        TaskPlan plan = service.createPlan("综合练习", LocalDate.now().plusDays(2), "每天1小时", source);

        assertEquals(3, plan.tasks().size());
        assertTrue(plan.tasks().stream().allMatch(task -> task.estimatedMinutes() <= 60));
        assertEquals(3, plan.tasks().stream().map(PlanTask::scheduledDate).distinct().count());
    }

    @Test
    void shouldReplanOnlyCurrentAndFollowingTasks() {
        LocalDate today = LocalDate.now();
        TaskPlan plan = service.createPlan("复习", today.plusDays(3), "每天1小时", List.of(
                new PlanTask("1", "第一章", "", 60, "high", "", "completed"),
                new PlanTask("2", "第二章", "", 60, "high", "", "pending"),
                new PlanTask("3", "第三章", "", 60, "high", "", "pending")));
        PlanTask first = plan.tasks().getFirst();
        PlanTask second = plan.tasks().get(1);

        TaskPlan updated = service.replanFrom(plan, second.id(), today.plusDays(2));

        assertEquals(first.scheduledDate(), updated.tasks().getFirst().scheduledDate());
        assertFalse(LocalDate.parse(updated.tasks().get(1).scheduledDate()).isBefore(today.plusDays(2)));
    }

    @Test
    void shouldNeverOverflowDailyCapacityWhenTasksNeedFragmentation() {
        List<PlanTask> source = List.of(
                new PlanTask("1", "任务一", "", 40, "high", "", "pending"),
                new PlanTask("2", "任务二", "", 40, "high", "", "pending"),
                new PlanTask("3", "任务三", "", 40, "high", "", "pending"));

        TaskPlan plan = service.createPlan("两天完成", LocalDate.now().plusDays(1), "每天1小时", source);

        var minutesByDay = plan.tasks().stream().collect(java.util.stream.Collectors.groupingBy(
                PlanTask::scheduledDate, java.util.stream.Collectors.summingInt(PlanTask::estimatedMinutes)));
        assertEquals(120, plan.tasks().stream().mapToInt(PlanTask::estimatedMinutes).sum());
        assertTrue(minutesByDay.values().stream().allMatch(minutes -> minutes <= 60));
    }
}
