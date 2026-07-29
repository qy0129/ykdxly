package com.example.ilink.application.skill;

import java.util.List;
import java.util.Objects;

/** skill.json 对应的稳定接口协议。 */
public record SkillDefinition(
        String name,
        String description,
        String version,
        boolean enabled,
        boolean requiresApproval,
        List<String> toolNames,
        List<SkillCapability> capabilities) {

    public SkillDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        version = version == null || version.isBlank() ? "1.0.0" : version.trim();
        toolNames = toolNames == null ? List.of() : List.copyOf(toolNames);
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
    }
}
