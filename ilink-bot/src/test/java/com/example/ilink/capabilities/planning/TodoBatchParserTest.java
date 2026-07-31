package com.example.ilink.capabilities.planning;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TodoBatchParserTest {
    private final TodoBatchParser parser = new TodoBatchParser();

    @Test
    void splitsIndependentTodosAndKeepsTheirTimes() {
        var drafts = parser.parse("明天上午十点提醒我提交周报，下午三点提醒我给客户打电话，晚上八点提醒我学习英语");

        assertEquals(3, drafts.size());
        assertEquals("提交周报", drafts.get(0).title());
        assertEquals("给客户打电话", drafts.get(1).title());
        assertEquals("学习英语", drafts.get(2).title());
        drafts.forEach(draft -> assertNotNull(draft.dueAt()));
        assertEquals(LocalDate.now().plusDays(1), drafts.get(1).dueAt().toLocalDate());
        assertEquals(15, drafts.get(1).dueAt().getHour());
        assertEquals(20, drafts.get(2).dueAt().getHour());
    }

    @Test
    void parsesMultipleTasksWithoutRepeatingReminderWords() {
        var drafts = parser.parse("明天上午十点提交周报，下午三点给客户打电话，晚上八点复习算法");

        assertEquals(3, drafts.size());
        assertEquals("提交周报", drafts.get(0).title());
        assertEquals("给客户打电话", drafts.get(1).title());
        assertEquals("复习算法", drafts.get(2).title());
        assertEquals(LocalDate.now().plusDays(1), drafts.get(0).dueAt().toLocalDate());
        assertEquals(10, drafts.get(0).dueAt().getHour());
        assertEquals(15, drafts.get(1).dueAt().getHour());
        assertEquals(20, drafts.get(2).dueAt().getHour());
    }

    @Test
    void ignoresTodoContainerAndUsesPeriodDefaultTime() {
        var drafts = parser.parse("帮我设置一下待办,明天上午点奶茶,同时后天 10 点点外卖");

        assertEquals(2, drafts.size());
        assertEquals("点奶茶", drafts.get(0).title());
        assertEquals(9, drafts.get(0).dueAt().getHour());
        assertEquals("点外卖", drafts.get(1).title());
        assertEquals(10, drafts.get(1).dueAt().getHour());
    }

    @Test
    void splitsLineBasedTodoListAndIgnoresGlobalInstructions() {
        var drafts = parser.parse("新建以下待办事项：\n"
                + "今晚 20:00 学习 Python 两小时\n"
                + "周六早上 10:30 规划下周健身计划\n"
                + "周日下午 整理所有学习打卡记录\n"
                + "每条任务临近前半小时推送提醒\n"
                + "后续你可以定期检查我完成情况");

        assertEquals(3, drafts.size());
        assertEquals("学习 Python 两小时", drafts.get(0).title());
        assertEquals(20, drafts.get(0).dueAt().getHour());
        assertEquals("规划健身计划", drafts.get(1).title());
        assertEquals(10, drafts.get(1).dueAt().getHour());
        assertEquals("整理所有学习打卡记录", drafts.get(2).title());
        assertEquals(14, drafts.get(2).dueAt().getHour());
    }

    @Test
    void ignoresDailyReflectionInstructionAsTodoItem() {
        var drafts = parser.parse("明天上午八点学习 Python，每天晚上十点检查完成情况");

        assertEquals(1, drafts.size());
        assertEquals("学习 Python", drafts.getFirst().title());
    }
}
