package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InboundMessageValidatorTest {
    private final InboundMessageValidator validator = new InboundMessageValidator();

    @Test
    void rejectsEmptyTextAndFutureMessages() {
        IncomingMessage empty = new IncomingMessage(
                AgentIdentity.direct("u1"), List.of(new MessagePart.Text("  ")));
        assertFalse(validator.validate(empty).valid());

        IncomingMessage future = new IncomingMessage(
                AgentIdentity.direct("u1"), List.of(new MessagePart.Text("ok")),
                "m2", Instant.now().plusSeconds(11 * 60), "PRIVATE");
        assertFalse(validator.validate(future).valid());
    }

    @Test
    void normalizesCompatibilityCharactersBeforeModelInput() {
        assertEquals("hello world", validator.normalizeText(" ｈｅｌｌｏ　ｗｏｒｌｄ "));
        assertTrue(validator.validate(new IncomingMessage(
                AgentIdentity.direct("u1"), List.of(new MessagePart.Text("ok")))).valid());
    }
}
