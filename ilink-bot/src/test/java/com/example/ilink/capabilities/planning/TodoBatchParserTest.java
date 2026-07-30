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
}
