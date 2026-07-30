package com.example.ilink.application.routing;

/** 路由可选的一项能力，能力之间没有优先级。 */
public record CapabilityDefinition(
        String name,
        String description,
        String parameterHint,
        boolean interactive) {
}
