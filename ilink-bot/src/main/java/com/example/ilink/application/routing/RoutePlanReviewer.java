package com.example.ilink.application.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 对每次路由计划进行确定性审计，阻止不满足前置条件的错误动作进入执行器。 */
public final class RoutePlanReviewer {

    private final CapabilityContractValidator validator = new CapabilityContractValidator();

    public Review review(IntentPlan plan, IntentContext context) {
        List<IntentAction> corrected = new ArrayList<>();
        List<String> prompts = new ArrayList<>();
        for (IntentAction action : plan.actions()) {
            CapabilityContractValidator.Validation validation = validator.validate(action.requestText(), action.route(),
                    new CapabilityContractValidator.Context(context.pendingDraw(),
                            context.pendingImage() || context.hasLastImage(), context.hasDocument()));
            if (validation.decision() == CapabilityContractValidator.Decision.REQUEST_INPUT) {
                prompts.add(validation.message());
                continue;
            }
            if (validation.decision() == CapabilityContractValidator.Decision.FALLBACK_CHAT) {
                corrected.add(new IntentAction(action.requirementId(), action.requestText(),
                        action.dependsOn(), IntentResult.chat()));
            } else {
                corrected.add(action);
            }
        }
        Set<String> retainedIds = corrected.stream().map(IntentAction::requirementId)
                .collect(java.util.stream.Collectors.toSet());
        List<IntentAction> independent = corrected.stream()
                .map(action -> new IntentAction(action.requirementId(), action.requestText(),
                        action.dependsOn().stream().filter(retainedIds::contains).toList(), action.route()))
                .toList();
        IntentPlan reviewed = new IntentPlan(independent, plan.messageMode());
        return prompts.isEmpty() ? Review.accept(reviewed)
                : new Review(reviewed, String.join("\n", prompts.stream().distinct().toList()));
    }

    public record Review(IntentPlan plan, String prompt) {
        static Review accept(IntentPlan plan) { return new Review(plan, ""); }
        static Review requestInput(String prompt) { return new Review(null, prompt); }
        public boolean needsInput() { return prompt != null && !prompt.isBlank(); }
    }
}
