package com.example.ilink.routing;

public record IntentResult(
        String intent,
        String enPrompt,
        String cnDescription,
        String imageSize,
        String replyMode,
        String voiceStyle,
        String persona,
        String imageAction,
        String imagePrompt,
        String audioSource,
        int audioIndex,
        String documentAction,
        String outputFileType) {
}
