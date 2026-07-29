package com.example.ilink.application.routing;

/** 路由模型可选择的一项机器人能力。 */
public record BotSkill(String name, String description, java.util.Set<String> toolNames, boolean enabled) {
    public BotSkill {
        toolNames = toolNames == null ? java.util.Set.of() : java.util.Set.copyOf(toolNames);
    }

    public BotSkill(String name, String description) {
        this(name, description, java.util.Set.of(), true);
    }
}
