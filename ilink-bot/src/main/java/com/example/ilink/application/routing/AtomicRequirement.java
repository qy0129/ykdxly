package com.example.ilink.application.routing;

import java.util.List;

/** 第一阶段从用户原话中拆出的、不可再分的一个需求。 */
public record AtomicRequirement(String id, String text, List<String> dependsOn) {
    public AtomicRequirement {
        id = id == null ? "" : id.trim();
        text = text == null ? "" : text.trim();
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }
}
