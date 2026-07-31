package com.example.ilink.application.routing;

import com.example.ilink.application.skill.SkillManager;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 路由能力的唯一注册表；提示词、校验和执行计划都以这里为准。 */
public final class CapabilityRegistry {

    private final Map<String, CapabilityDefinition> capabilities;

    public CapabilityRegistry(List<CapabilityDefinition> definitions) {
        Map<String, CapabilityDefinition> indexed = new LinkedHashMap<>();
        for (CapabilityDefinition definition : definitions) indexed.put(definition.name(), definition);
        RoutingGuideCatalog.verifyCoverage(indexed.keySet());
        capabilities = Map.copyOf(indexed);
    }

    public List<CapabilityDefinition> all() {
        return capabilities.values().stream().toList();
    }

    public Set<String> names() {
        return capabilities.keySet();
    }

    public boolean contains(String name) {
        return capabilities.containsKey(name);
    }

    public static CapabilityRegistry defaults() {
        return SkillManager.loadDefault(null).capabilityRegistry();
    }
}
