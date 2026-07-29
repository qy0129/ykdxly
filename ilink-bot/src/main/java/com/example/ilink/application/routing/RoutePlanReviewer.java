package com.example.ilink.application.routing;

import java.util.ArrayList;
import java.util.List;

/** 对每次路由计划进行确定性审计，阻止不满足前置条件的错误动作进入执行器。 */
public final class RoutePlanReviewer {

    private final CapabilityContractValidator validator = new CapabilityContractValidator();

    public Review review(IntentPlan plan, IntentContext context) {
        List<IntentAction> corrected = new ArrayList<>();
        for (IntentAction action : plan.actions()) {
            CapabilityContractValidator.Validation validation = validator.validate(action.requestText(), action.route(),
                    new CapabilityContractValidator.Context(context.pendingDraw(),
                            context.pendingImage() || context.hasLastImage(), context.hasDocument()));
            if (validation.decision() == CapabilityContractValidator.Decision.REQUEST_INPUT) {
                return Review.requestInput(validation.message());
            }
            if (validation.decision() == CapabilityContractValidator.Decision.FALLBACK_CHAT) {
                corrected.add(new IntentAction(action.requestText(), IntentResult.chat()));
            } else {
                corrected.add(action);
            }
        }
        return Review.accept(new IntentPlan(corrected));
    }

    public record Review(IntentPlan plan, String prompt) {
        static Review accept(IntentPlan plan) { return new Review(plan, ""); }
        static Review requestInput(String prompt) { return new Review(null, prompt); }
        public boolean needsInput() { return prompt != null && !prompt.isBlank(); }
    }
}
