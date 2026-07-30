package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundMessageReceiptStoreTest {
    @Test
    void acceptsOneCopyAndSkipsLaterCopies() {
        InboundMessageReceiptStore store = new InboundMessageReceiptStore(false);
        var first = store.claim("wechat", "bot", "message-1", "user", Instant.now());
        var concurrent = store.claim("wechat", "bot", "message-1", "user", Instant.now());

        assertTrue(first.accepted());
        assertEquals(InboundMessageReceiptStore.Status.PROCESSING, concurrent.status());

        store.complete(first);
        assertTrue(store.claim("wechat", "bot", "message-1", "user", Instant.now()).completed());
    }
}
