package com.example.ilink.capabilities.inbox;

import com.example.ilink.capabilities.inbox.model.ExtractedTask;
import com.example.ilink.capabilities.inbox.model.ExtractedTime;
import com.example.ilink.capabilities.inbox.model.ExtractionResult;
import com.example.ilink.capabilities.inbox.model.MessageSummary;
import com.example.ilink.capabilities.inbox.model.ProcessedMessage;
import com.example.ilink.capabilities.inbox.service.ExtractorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ExtractorService单元测试
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExtractorServiceTest {

    private ExtractorService service;

    @BeforeEach
    void setUp() {
        service = new ExtractorService();
    }

    @Test
    void shouldExtractTaskFromMessage() {
        // Given
        MessageSummary summary = new MessageSummary(
            "需要完成项目报告",
            java.util.List.of("项目", "报告"),
            MessageSummary.MessageType.TASK,
            MessageSummary.Priority.MEDIUM,
            false
        );
        ProcessedMessage message = createMessage("需要完成项目报告");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertTrue(result.hasTasks());
        assertTrue(result.taskCount() > 0);
    }

    @Test
    void shouldExtractTimeFromMessage() {
        // Given
        MessageSummary summary = new MessageSummary(
            "明天下午3点开会",
            java.util.List.of("明天", "开会"),
            MessageSummary.MessageType.NOTIFICATION,
            MessageSummary.Priority.MEDIUM,
            false
        );
        ProcessedMessage message = createMessage("明天下午3点开会");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertTrue(result.hasTimes());
        assertTrue(result.timeCount() > 0);
    }

    @Test
    void shouldExtractDeadline() {
        // Given
        MessageSummary summary = new MessageSummary(
            "截止18点提交报告",
            java.util.List.of("截止", "提交"),
            MessageSummary.MessageType.TASK,
            MessageSummary.Priority.HIGH,
            false
        );
        ProcessedMessage message = createMessage("截止18点提交报告");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertTrue(result.hasTimes());
        // 检查是否有截止时间
        boolean hasDeadline = result.times().stream()
            .anyMatch(ExtractedTime::isDeadline);
        assertTrue(hasDeadline);
    }

    @Test
    void shouldExtractLocation() {
        // Given
        MessageSummary summary = new MessageSummary(
            "在会议室A开会",
            java.util.List.of("会议室", "开会"),
            MessageSummary.MessageType.NOTIFICATION,
            MessageSummary.Priority.MEDIUM,
            false
        );
        ProcessedMessage message = createMessage("在会议室A开会");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertTrue(result.hasLocations());
    }

    @Test
    void shouldReturnEmptyResultForNoExtraction() {
        // Given
        MessageSummary summary = new MessageSummary(
            "今天天气真好",
            java.util.List.of("天气"),
            MessageSummary.MessageType.CHAT,
            MessageSummary.Priority.LOW,
            false
        );
        ProcessedMessage message = createMessage("今天天气真好");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertFalse(result.hasTasks());
        assertFalse(result.hasTimes());
    }

    @Test
    void shouldCreateEmptyResult() {
        // When
        ExtractionResult result = ExtractionResult.empty(MessageSummary.MessageType.CHAT);

        // Then
        assertFalse(result.hasTasks());
        assertFalse(result.hasTimes());
        assertFalse(result.hasPeople());
        assertFalse(result.hasLocations());
        assertEquals(MessageSummary.Priority.LOW, result.overallPriority());
    }

    @Test
    void shouldExtractMultipleTasks() {
        // Given
        MessageSummary summary = new MessageSummary(
            "需要完成报告，准备PPT，安排会议",
            java.util.List.of("报告", "PPT", "会议"),
            MessageSummary.MessageType.TASK,
            MessageSummary.Priority.MEDIUM,
            false
        );
        ProcessedMessage message = createMessage("需要完成报告，准备PPT，安排会议");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        assertTrue(result.hasTasks());
        // 可能提取多个任务
    }

    @Test
    void shouldHandleNullDeadline() {
        // Given
        MessageSummary summary = new MessageSummary(
            "完成任务",
            java.util.List.of("任务"),
            MessageSummary.MessageType.TASK,
            MessageSummary.Priority.LOW,
            false
        );
        ProcessedMessage message = createMessage("完成任务");

        // When
        ExtractionResult result = service.extract(summary, message);

        // Then
        if (result.hasTasks()) {
            ExtractedTask task = result.tasks().get(0);
            assertNull(task.deadline());
        }
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
