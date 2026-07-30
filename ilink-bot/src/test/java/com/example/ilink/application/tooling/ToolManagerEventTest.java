package com.example.ilink.application.tooling;

import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.messaging.ChannelType;
import com.example.ilink.application.messaging.RequestLogContext;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolManagerEventTest {

    @Test
    void publishesToolStartAndCompletionSummaries() {
        ToolManager manager = new ToolManager().register(new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("document_question", "文档问答", "测试工具",
                        ToolDefinition.objectParameters(new JsonObject()), true);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonObject arguments) {
                return ToolResult.success("完成");
            }
        });
        List<AgentEvent> events = new ArrayList<>();

        try (RequestLogContext.Scope ignored = RequestLogContext.open(
                ChannelType.WEB, "web-user", "session-1", "request-1", events::add)) {
            manager.execute("document_question", new ToolContext("web-user"), new JsonObject());
        }

        assertEquals(List.of("调用工具：文档问答", "工具完成：文档问答"),
                events.stream().map(AgentEvent::content).toList());
        assertEquals(List.of("running", "success"), events.stream()
                .map(event -> event.metadata().get("status")).toList());
    }
}
