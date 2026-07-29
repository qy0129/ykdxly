package com.example.ilink.application.skill;

/** Skill 暴露给意图路由的一项能力。 */
public record SkillCapability(String name, String description, String parameterHint, boolean interactive) {
    public SkillCapability {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        parameterHint = parameterHint == null ? "" : parameterHint.trim();
    }
}
