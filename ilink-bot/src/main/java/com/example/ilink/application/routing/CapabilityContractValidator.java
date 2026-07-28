package com.example.ilink.application.routing;

/** 在工具执行前校验能力的必要条件，模型输出只能作为候选动作。 */
public final class CapabilityContractValidator {

    public Validation validate(String requestText, IntentResult route, Context context) {
        String intent = route.intent();
        if ("draw".equals(intent) && !IntentPolicy.isExplicitImageCreation(requestText)) {
            return Validation.fallbackToChat();
        }
        if ("draw_size".equals(intent) && !context.pendingDraw()) {
            return Validation.requestInput("当前没有等待确认尺寸的绘图请求。");
        }
        if ("image_action".equals(intent) && !context.hasImage()) {
            return Validation.requestInput("请先发送需要处理的图片，或者明确回复上一张图片。");
        }
        if (("document_summary".equals(intent) || "document_question".equals(intent)
                || "document_edit".equals(intent)) && !context.hasDocument()) {
            return Validation.requestInput("请先发送需要处理的文档。");
        }
        if ("generate_file".equals(intent) && !IntentPolicy.hasExplicitFileRequest(requestText)) {
            return Validation.fallbackToChat();
        }
        if ("nearby_food".equals(intent)
                && !IntentPolicy.isNearbyDiningRequest(requestText)
                && !IntentPolicy.isExplicitLocationRememberRequest(requestText)) {
            return Validation.fallbackToChat();
        }
        if ("food_order".equals(intent) && !IntentPolicy.isExplicitFoodOrderRequest(requestText)) {
            return Validation.fallbackToChat();
        }
        return Validation.allow();
    }

    public record Context(boolean pendingDraw, boolean hasImage, boolean hasDocument) {
    }

    public enum Decision { ALLOW, FALLBACK_CHAT, REQUEST_INPUT }

    public record Validation(Decision decision, String message) {
        public boolean allowed() {
            return decision == Decision.ALLOW;
        }

        public static Validation allow() {
            return new Validation(Decision.ALLOW, "");
        }

        public static Validation fallbackToChat() {
            return new Validation(Decision.FALLBACK_CHAT, "");
        }

        public static Validation requestInput(String message) {
            return new Validation(Decision.REQUEST_INPUT, message);
        }
    }
}
