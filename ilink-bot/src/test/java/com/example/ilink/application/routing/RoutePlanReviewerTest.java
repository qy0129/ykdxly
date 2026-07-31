package com.example.ilink.application.routing;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlanReviewerTest {

    @Test
    void keepsIndependentActionsWhenAnotherActionNeedsInput() {
        IntentResult imageAction = new Gson().fromJson(
                "{\"intent\":\"image_action\",\"replyMode\":\"keep\",\"imageAction\":\"edit\"}",
                IntentResult.class);
        IntentPlan plan = new IntentPlan(List.of(
                new IntentAction("r1", "修改上一张图", List.of(), imageAction),
                new IntentAction("r2", "解释一下原因", List.of(), IntentResult.chat())));

        RoutePlanReviewer.Review review = new RoutePlanReviewer().review(plan,
                new IntentContext(false, false, false, false, false));

        assertTrue(review.needsInput());
        assertEquals(List.of("r2"), review.plan().actions().stream()
                .map(IntentAction::requirementId).toList());
    }

    @Test
    void fallbackToChatPreservesActionIdentity() {
        IntentResult invalidDraw = new Gson().fromJson(
                "{\"intent\":\"draw\",\"replyMode\":\"keep\"}", IntentResult.class);
        IntentPlan plan = new IntentPlan(List.of(
                new IntentAction("r7", "介绍图片生成能力", List.of(), invalidDraw)));

        RoutePlanReviewer.Review review = new RoutePlanReviewer().review(plan,
                new IntentContext(false, false, false, false, false));

        assertEquals("r7", review.plan().actions().getFirst().requirementId());
        assertEquals("chat", review.plan().actions().getFirst().route().intent());
    }
}
