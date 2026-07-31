package com.example.ilink.application.routing;

import com.example.ilink.capabilities.documents.DocumentFileType;

/** 在工具执行前校验能力的必要条件，模型输出只能作为候选动作。 */
public final class CapabilityContractValidator {
    private static final java.util.Set<String> REPLY_MODES = java.util.Set.of("keep", "text", "voice", "both");

    public Validation validate(String requestText, IntentResult route, Context context) {
        if (route == null || route.intent() == null || route.intent().isBlank()) {
            return Validation.fallbackToChat();
        }
        String intent = route.intent();
        if (!REPLY_MODES.contains(route.replyMode())) {
            return Validation.requestInput("回复方式参数无效，请重新说明需要文字还是语音回复。");
        }
        if ("audio_transcribe".equals(intent) && route.audioIndex() < 1) {
            return Validation.requestInput("语音序号必须从 1 开始。");
        }
        if ("calendar_event".equals(intent)
                && (route.calendarReminderMinutes() < 0 || route.calendarReminderMinutes() > 10_080)) {
            return Validation.requestInput("提醒提前时间必须在 0 到 10080 分钟之间。");
        }
        if ("todo".equals(intent) && (requestText == null || requestText.isBlank())) {
            return Validation.requestInput("请告诉我待办的具体内容。");
        }
        if ("taxi_trip".equals(intent) && requestText != null && requestText.matches(".*(打车|叫车).*去.*")
                && route.travelDestination().isBlank()) {
            return Validation.requestInput("请告诉我打车目的地。");
        }
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
        if ("generate_file".equals(intent)) {
            String outputType = DocumentFileType.canonical(route.outputFileType());
            if (DocumentFileType.isPresentation(outputType)) {
                return Validation.requestInput("当前支持识别和编辑 PPT/PPTX，但暂不支持从零生成演示文稿。请选择 "
                        + DocumentFileType.generatableLabel() + "。 ");
            }
            if (!"none".equals(outputType) && !DocumentFileType.canGenerate(outputType)) {
                return Validation.requestInput("暂不支持生成该格式，请选择 " + DocumentFileType.generatableLabel() + "。 ");
            }
        }
        if ("document_edit".equals(intent)) {
            String outputType = DocumentFileType.canonical(route.outputFileType());
            if (!"none".equals(outputType) && !DocumentFileType.canEditOutput(outputType)) {
                return Validation.requestInput("暂不支持将文档编辑为该格式。 ");
            }
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
