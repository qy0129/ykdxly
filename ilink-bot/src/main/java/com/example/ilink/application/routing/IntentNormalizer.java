package com.example.ilink.application.routing;

/** 修正模型返回的未知意图。 */
public final class IntentNormalizer {

    private static final java.util.Map<String, String> ALIASES = java.util.Map.of(
            "document_generate", "generate_file",
            "generate_document", "generate_file",
            "draw_image", "draw",
            "reminder", "calendar_event",
            "nearby_food_search", "nearby_food");

    private final CapabilityRegistry capabilities;

    public IntentNormalizer(CapabilityRegistry capabilities) {
        this.capabilities = capabilities;
    }

    public String normalizeIntent(String intent) {
        String value = intent == null ? "" : intent.trim();
        if (capabilities.contains(value)) return value;
        String alias = ALIASES.get(value);
        return alias != null && capabilities.contains(alias) ? alias : "chat";
    }

    public boolean isKnown(String intent) {
        String value = intent == null ? "" : intent.trim();
        return capabilities.contains(value) || ALIASES.containsKey(value);
    }
}
