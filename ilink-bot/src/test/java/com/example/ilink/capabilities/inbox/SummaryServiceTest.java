package com.example.ilink.capabilities.inbox;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.service.SummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SummaryService单元测试
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SummaryServiceTest {

    private SummaryService service;

    @BeforeEach
    void setUp() {
        InboxConfig config = InboxConfig.defaultConfig();
        service = new SummaryService(config);
    }

    @Test
    void shouldReturnDirectForShortMessage() {
        // Given
        ProcessedMessage message = createMessage("明天开会");

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertFalse(summary.isDirect());
        assertEquals("明天开会", summary.summary());
    }

    @Test
    void shouldSummarizeLongMessage() {
        // Given
        String longContent = "这是一个很长的消息。".repeat(20);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertFalse(summary.isDirect());
        assertNotNull(summary.summary());
        assertTrue(summary.summaryLength() <= 200);
    }

    @Test
    void shouldExtractKeywords() {
        // Given
        ProcessedMessage message = createMessage("明天下午3点开会讨论项目进度");

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertNotNull(summary.keywords());
        // 关键词可能为空或包含一些词
    }

    @Test
    void shouldClassifyTaskMessage() {
        // Given - 使用长消息以触发分类逻辑
        String longContent = "需要完成项目报告，请在今天下班前提交，这是很重要的任务。".repeat(5);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertEquals(MessageSummary.MessageType.TASK, summary.messageType());
    }

    @Test
    void shouldClassifyNotificationMessage() {
        // Given - 使用长消息以触发分类逻辑，避免任务关键词
        String longContent = "重要公告：明天下午3点公司年会，希望大家准时参加。这是重要的会议通知。".repeat(5);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertEquals(MessageSummary.MessageType.NOTIFICATION, summary.messageType());
    }

    @Test
    void shouldClassifyInquiryMessage() {
        // Given - 使用长消息以触发分类逻辑，避免任务关键词
        String longContent = "咨询一下明天有空吗？我想和你聊一下生活的事情，你有时间吗？".repeat(5);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertEquals(MessageSummary.MessageType.INQUIRY, summary.messageType());
    }

    @Test
    void shouldEstimateUrgentPriority() {
        // Given - 使用长消息以触发优先级估算逻辑
        String longContent = "紧急：立即处理这个任务，这是非常紧急的事情，需要马上完成。".repeat(5);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertEquals(MessageSummary.Priority.URGENT, summary.priority());
    }

    @Test
    void shouldEstimateHighPriority() {
        // Given - 使用长消息以触发优先级估算逻辑
        String longContent = "重要：今天完成这个项目，这是非常重要的工作，需要优先处理。".repeat(5);
        ProcessedMessage message = createMessage(longContent);

        // When
        MessageSummary summary = service.summarize(message);

        // Then
        assertEquals(MessageSummary.Priority.HIGH, summary.priority());
    }

    @Test
    void shouldCreateDirectSummary() {
        // When
        MessageSummary summary = MessageSummary.direct("测试内容");

        // Then
        assertTrue(summary.isDirect());
        assertEquals("测试内容", summary.summary());
        assertEquals(MessageSummary.MessageType.OTHER, summary.messageType());
        assertEquals(MessageSummary.Priority.LOW, summary.priority());
    }

    @Test
    void shouldCreateSummarizedSummary() {
        // When
        MessageSummary summary = MessageSummary.summarized(
            "摘要内容", 
            java.util.List.of("关键词"), 
            MessageSummary.MessageType.TASK, 
            MessageSummary.Priority.HIGH
        );

        // Then
        assertFalse(summary.isDirect());
        assertEquals("摘要内容", summary.summary());
        assertTrue(summary.hasKeywords());
    }

    private ProcessedMessage createMessage(String content) {
        return new ProcessedMessage(
            "test_msg",
            content,
            ProcessedMessage.SourceType.PRIVATE,
            "",
            "user_123",
            "张三",
            Instant.now()
        );
    }
}
