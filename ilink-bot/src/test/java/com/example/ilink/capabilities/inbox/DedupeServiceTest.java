package com.example.ilink.capabilities.inbox;

import com.example.ilink.capabilities.inbox.config.InboxConfig;
import com.example.ilink.capabilities.inbox.model.DedupeResult;
import com.example.ilink.capabilities.inbox.service.DedupeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DedupeService单元测试
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DedupeServiceTest {

    private DedupeService service;

    @BeforeEach
    void setUp() {
        InboxConfig config = InboxConfig.defaultConfig();
        service = new DedupeService(config, null);
    }

    @Test
    void shouldDetectDuplicateByMessageId() {
        // Given
        service.record("msg_001", "内容", "user1");

        // When
        DedupeResult result = service.check("msg_001", "内容", "user1");

        // Then
        assertTrue(result.isDuplicate());
        assertEquals(DedupeResult.DUPLICATE_MSG_ID, result.reason());
    }

    @Test
    void shouldDetectDuplicateByContentHash() {
        // Given
        service.record("msg_001", "明天下午3点开会", "user1");

        // When - 不同ID但相同内容
        DedupeResult result = service.check("msg_002", "明天下午3点开会", "user1");

        // Then
        assertTrue(result.isDuplicate());
        assertEquals(DedupeResult.DUPLICATE_CONTENT_HASH, result.reason());
    }

    @Test
    void shouldNotMarkDifferentContentAsDuplicate() {
        // Given
        service.record("msg_001", "明天下午3点开会", "user1");

        // When
        DedupeResult result = service.check("msg_002", "后天上午10点开会", "user1");

        // Then
        assertFalse(result.isDuplicate());
        assertNull(result.reason());
    }

    @Test
    void shouldNotMarkDifferentSenderAsDuplicate() {
        // Given
        service.record("msg_001", "明天下午3点开会", "user1");

        // When - 不同发送者
        DedupeResult result = service.check("msg_002", "明天下午3点开会", "user2");

        // Then
        assertFalse(result.isDuplicate());
    }

    @Test
    void shouldHandleNullContent() {
        // Given
        service.record("msg_001", null, "user1");

        // When
        DedupeResult result = service.check("msg_002", null, "user1");

        // Then
        assertTrue(result.isDuplicate());
    }

    @Test
    void shouldHandleEmptyContent() {
        // Given
        service.record("msg_001", "", "user1");

        // When
        DedupeResult result = service.check("msg_002", "", "user1");

        // Then
        assertTrue(result.isDuplicate());
    }

    @Test
    void shouldCreateUniqueResult() {
        // When
        DedupeResult result = DedupeResult.unique();

        // Then
        assertFalse(result.isDuplicate());
        assertNull(result.reason());
    }

    @Test
    void shouldCreateDuplicateResult() {
        // When
        DedupeResult result = DedupeResult.duplicate("TEST_REASON");

        // Then
        assertTrue(result.isDuplicate());
        assertEquals("TEST_REASON", result.reason());
    }

    @Test
    void shouldTrackStats() {
        // Given
        service.record("msg_001", "内容1", "user1");
        service.record("msg_002", "内容2", "user1");

        // When
        service.check("msg_003", "内容3", "user1");

        // Then
        DedupeService.DedupeStats stats = service.getStats();
        assertTrue(stats.messageIdCacheSize() > 0);
        assertTrue(stats.contentHashCacheSize() > 0);
    }
}
