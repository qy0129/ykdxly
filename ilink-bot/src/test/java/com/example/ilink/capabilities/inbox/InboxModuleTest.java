package com.example.ilink.capabilities.inbox;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.MessageResult;
import com.example.ilink.capabilities.inbox.model.RawMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InboxModule单元测试
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InboxModuleTest {

    private InboxModule module;

    @BeforeEach
    void setUp() {
        InboxConfig config = InboxConfig.defaultConfig();
        module = new InboxModule(config);
    }

    @Test
    void shouldProcessSimpleMessage() {
        // Given
        RawMessage message = RawMessage.fromWechat(
            "test_001", "user_123", "张三",
            "明天下午3点开会"
        );

        // When
        MessageResult result = module.process(message);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertFalse(result.isDuplicate());
        assertNotNull(result.summary());
    }

    @Test
    void shouldDetectDuplicateMessage() {
        // Given
        RawMessage msg1 = RawMessage.fromWechat(
            "msg_001", "user_123", "张三",
            "明天下午3点开会"
        );
        RawMessage msg2 = RawMessage.fromWechat(
            "msg_002", "user_123", "张三",
            "明天下午3点开会"
        );

        // When
        module.process(msg1);
        MessageResult result = module.process(msg2);

        // Then
        assertTrue(result.isDuplicate());
        assertNotNull(result.statusReason());
    }

    @Test
    void shouldNotMarkDifferentContentAsDuplicate() {
        // Given
        RawMessage msg1 = RawMessage.fromWechat(
            "msg_001", "user_123", "张三",
            "明天下午3点开会"
        );
        RawMessage msg2 = RawMessage.fromWechat(
            "msg_002", "user_123", "张三",
            "后天上午10点开会"
        );

        // When
        module.process(msg1);
        MessageResult result = module.process(msg2);

        // Then
        assertTrue(result.isSuccess());
    }

    @Test
    void shouldExtractTaskFromMessage() {
        // Given
        RawMessage message = RawMessage.fromWechat(
            "test_002", "user_123", "张三",
            "需要完成项目报告，请在今天下班前提交"
        );

        // When
        MessageResult result = module.process(message);

        // Then
        assertTrue(result.isSuccess());
        assertTrue(result.hasTasks());
        assertTrue(result.taskCount() > 0);
    }

    @Test
    void shouldExtractTimeFromMessage() {
        // Given
        RawMessage message = RawMessage.fromWechat(
            "test_003", "user_123", "张三",
            "明天下午3点在会议室A开会"
        );

        // When
        MessageResult result = module.process(message);

        // Then
        assertTrue(result.isSuccess());
        assertTrue(result.hasTimes());
        assertTrue(result.timeCount() > 0);
    }

    @Test
    void shouldHandleEmptyMessage() {
        // Given
        RawMessage message = RawMessage.fromWechat(
            "test_004", "user_123", "张三",
            ""
        );

        // When
        MessageResult result = module.process(message);

        // Then
        assertNotNull(result);
        // 空消息可能成功或失败，但不应抛出异常
    }

    @Test
    void shouldHandleLongMessage() {
        // Given
        String longContent = "这是一个很长的消息。".repeat(100);
        RawMessage message = RawMessage.fromWechat(
            "test_005", "user_123", "张三",
            longContent
        );

        // When
        MessageResult result = module.process(message);

        // Then
        assertNotNull(result);
        assertTrue(result.isSuccess());
    }

    @Test
    void shouldProcessBatchMessages() {
        // Given
        java.util.List<RawMessage> messages = java.util.List.of(
            RawMessage.fromWechat("batch_001", "user_123", "张三", "消息1"),
            RawMessage.fromWechat("batch_002", "user_123", "张三", "消息2"),
            RawMessage.fromWechat("batch_003", "user_123", "张三", "消息3")
        );

        // When
        java.util.List<MessageResult> results = module.processBatch(messages);

        // Then
        assertEquals(3, results.size());
        results.forEach(result -> assertNotNull(result));
    }

    @Test
    void shouldTrackStats() {
        // Given
        RawMessage msg1 = RawMessage.fromWechat("stats_001", "user_123", "张三", "消息1");
        RawMessage msg2 = RawMessage.fromWechat("stats_002", "user_123", "张三", "消息1"); // 重复

        // When
        module.process(msg1);
        module.process(msg2);

        // Then
        InboxModule.InboxStats stats = module.getStats();
        assertEquals(2, stats.totalProcessed());
        assertEquals(1, stats.totalDuplicates());
        assertEquals(0, stats.totalFailed());
    }

    @Test
    void shouldResetStats() {
        // Given
        RawMessage msg = RawMessage.fromWechat("reset_001", "user_123", "张三", "消息");
        module.process(msg);

        // When
        module.resetStats();

        // Then
        InboxModule.InboxStats stats = module.getStats();
        assertEquals(0, stats.totalProcessed());
    }
}
