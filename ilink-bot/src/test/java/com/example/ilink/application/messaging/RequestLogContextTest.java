package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestLogContextTest {

    @Test
    void identifiesWebRequestsAndRestoresPreviousContext() {
        assertEquals("[SYS][测试]", RequestLogContext.prefix("测试"));

        try (RequestLogContext.Scope ignored = RequestLogContext.open(
                ChannelType.WEB, "web-user", "session-1", "request-1")) {
            String prefix = RequestLogContext.prefix("任务开始");
            assertEquals("[W][r=request-][任务开始]", prefix);
        }

        assertEquals("[SYS][测试]", RequestLogContext.prefix("测试"));
    }

    @Test
    void labelsWechatAndKeepsLogPreviewsSingleLineAndBounded() {
        String prefix = RequestLogContext.prefix(
                ChannelType.WECHAT, "回复发送", "wechat-user", "", "");
        assertEquals("[WX][回复发送]", prefix);

        String preview = RequestLogContext.preview("first\n" + "x".repeat(180));
        assertFalse(preview.contains("\n"));
        assertTrue(preview.endsWith("...\""));
        assertTrue(preview.length() <= 126);

        try (RequestLogContext.Scope ignored = RequestLogContext.open(
                ChannelType.WECHAT, "wechat-user", "wechat-session", "")) {
            assertEquals("[WX][回复发送]",
                    RequestLogContext.prefixFor(ChannelType.WECHAT, "回复发送", "wechat-user"));
        }
    }

    @Test
    void forwardsPublicAgentEventsToTheCurrentRequestSink() {
        List<AgentEvent> events = new ArrayList<>();
        AgentEvent expected = new AgentEvent(AgentEvent.Type.TOOL_ACTIVITY,
                "调用工具：文档问答", Map.of("toolName", "document_question"));

        try (RequestLogContext.Scope ignored = RequestLogContext.open(
                ChannelType.WEB, "web-user", "session-1", "request-1", events::add)) {
            RequestLogContext.publish(expected);
        }

        assertEquals(List.of(expected), events);
        RequestLogContext.publish(expected);
        assertEquals(1, events.size(), "request scope must not leak after close");
    }
}
