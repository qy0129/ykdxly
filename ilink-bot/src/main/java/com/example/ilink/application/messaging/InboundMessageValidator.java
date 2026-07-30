package com.example.ilink.application.messaging;

import java.time.Duration;
import java.time.Instant;
import java.text.Normalizer;

/** 在写历史、调用模型和下载/处理媒体前校验统一入站格式。 */
public final class InboundMessageValidator {
    private static final int MAX_TEXT_LENGTH = 10_000;
    private static final int MAX_PARTS = 8;
    private static final int MAX_IMAGE_BYTES = 15 * 1024 * 1024;
    private static final int MAX_AUDIO_BYTES = 10 * 1024 * 1024;
    private static final int MAX_FILE_BYTES = 50 * 1024 * 1024;

    public Result validate(IncomingMessage message) {
        if (message == null) return Result.invalid("消息为空");
        if (message.principalId() == null || message.principalId().isBlank()
                || message.principalId().length() > 128) return Result.invalid("用户标识无效");
        if (message.messageId() == null || message.messageId().isBlank()
                || message.messageId().length() > 256) return Result.invalid("消息标识无效");
        if (message.parts().isEmpty() || message.parts().size() > MAX_PARTS) return Result.invalid("消息内容数量无效");
        Instant now = Instant.now();
        if (message.receivedAt().isAfter(now.plus(Duration.ofMinutes(10)))) return Result.invalid("消息时间无效");
        for (MessagePart part : message.parts()) {
            if (part == null) return Result.invalid("消息包含空内容");
            if (part instanceof MessagePart.Text text
                    && (text.text() == null || text.text().isBlank() || text.text().length() > MAX_TEXT_LENGTH)) {
                return Result.invalid("文字内容为空或过长");
            }
            if (part instanceof MessagePart.Image image && image.content().length > MAX_IMAGE_BYTES) {
                return Result.invalid("图片超过 15MB");
            }
            if (part instanceof MessagePart.Voice voice && voice.content().length > MAX_AUDIO_BYTES) {
                return Result.invalid("语音超过 10MB");
            }
            if (part instanceof MessagePart.File file && file.content().length > MAX_FILE_BYTES) {
                return Result.invalid("文件超过 50MB");
            }
        }
        return Result.ok();
    }

    public String normalizeText(String text) {
        if (text == null) return "";
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('\u3000', ' ')
                .replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", "")
                .trim();
        return normalized.length() <= MAX_TEXT_LENGTH
                ? normalized : normalized.substring(0, MAX_TEXT_LENGTH);
    }

    public record Result(boolean valid, String message) {
        static Result ok() { return new Result(true, ""); }
        static Result invalid(String message) { return new Result(false, message); }
    }
}
