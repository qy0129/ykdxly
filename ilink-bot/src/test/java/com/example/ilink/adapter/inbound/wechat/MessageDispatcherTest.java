package com.example.ilink.adapter.inbound.wechat;

import com.example.ilink.application.messaging.AgentIdentity;
import com.example.ilink.application.messaging.IncomingMessage;
import com.example.ilink.application.messaging.MessagePart;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDispatcherTest {

    @Test
    void recognizesDailyDashboardCommands() {
        assertTrue(MessageDispatcher.isDailyDashboardRequest(text("七日计划")));
        assertTrue(MessageDispatcher.isDailyDashboardRequest(text("打开七日计划页！")));
        assertTrue(MessageDispatcher.isDailyDashboardRequest(text(" 查看 七日计划 ")));
    }

    @Test
    void leavesNormalPlanningRequestsToTheMainRouter() {
        assertFalse(MessageDispatcher.isDailyDashboardRequest(text("帮我制定一个七日学习计划")));
        assertFalse(MessageDispatcher.isDailyDashboardRequest(text("查看所有计划")));
    }

    private IncomingMessage text(String value) {
        return new IncomingMessage(AgentIdentity.direct("user-1"),
                List.of(new MessagePart.Text(value)));
    }
}
