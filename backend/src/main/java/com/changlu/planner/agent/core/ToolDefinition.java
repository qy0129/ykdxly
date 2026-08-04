package com.changlu.planner.agent.core;

public record ToolDefinition(
    String name,
    String description,
    String executorType,
    boolean requiresConfirmation
) {}
