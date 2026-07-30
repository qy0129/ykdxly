package com.example.ilink.capabilities.visual;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunInteractionServiceTest {

    @Test
    void onlyAcceptsAnswersThatBelongToPendingQuiz() {
        FunInteractionService service = new FunInteractionService();
        service.handle("user", "每日答题");

        assertTrue(service.hasPending("user"));
        assertTrue(service.acceptsPendingReply("user", "A"));
        assertFalse(service.acceptsPendingReply("user", "查一下杭州天气"));

        service.handle("user", "A");
        assertFalse(service.hasPending("user"));
    }
}
