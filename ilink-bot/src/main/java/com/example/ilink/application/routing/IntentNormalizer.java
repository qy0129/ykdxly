package com.example.ilink.application.routing;

/** 修正模型返回的未知意图。 */
public final class IntentNormalizer {

    private final SkillRegistry skills;

    public IntentNormalizer(SkillRegistry skills) {
        this.skills = skills;
    }

    public String normalizeIntent(String intent) {
        return skills.names().contains(intent) ? intent : "chat";
    }
}
