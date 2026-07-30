package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.planning.PlanTask;
import com.example.ilink.capabilities.planning.TaskPlan;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PlanSessionStoreTest {

    @Test
    void shouldKeepMultiplePlansAndAllowSelection() {
        PlanSessionStore store = new PlanSessionStore(false);
        TaskPlan math = plan("PLAN-MATH", "学习高数");
        TaskPlan english = plan("PLAN-EN", "学习英语");

        store.set("user", math);
        store.set("user", english);

        assertEquals(2, store.list("user").size());
        assertEquals("PLAN-EN", store.get("user").id());
        assertEquals("PLAN-MATH", store.select("user", "高数").id());
        assertEquals("PLAN-MATH", store.get("user").id());

        store.clear("user");
        assertNull(store.get("user"));
        assertEquals(2, store.list("user").size());
    }

    private TaskPlan plan(String id, String goal) {
        return new TaskPlan(id, goal, LocalDate.now().plusDays(7).toString(), "每天1小时",
                LocalDate.now().toString(), List.of(new PlanTask(id + "-T1", "第一项", "",
                60, "high", LocalDate.now().toString(), "pending")));
    }
}
