package com.example.ilink.application.routing;

/** 修正模型返回的未知意图。 */
public final class IntentNormalizer {

    private final CapabilityRegistry capabilities;

    public IntentNormalizer(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public String normalizeIntent(String intent) {
        return capabilities.contains(intent) ? intent : "chat";
    }
}
