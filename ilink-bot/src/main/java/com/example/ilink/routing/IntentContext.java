package com.example.ilink.routing;

public record IntentContext(
        boolean pendingImage,
        boolean hasLastImage,
        boolean pendingDraw,
        boolean hasDocument) {
}
