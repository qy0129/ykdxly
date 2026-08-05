package com.changlu.planner.agent.subagents.image;

/** 单次文生图调用的结构化结果。 */
public record ImageGenerationResult(
    String requestId,
    String status,
    String imageUrl,
    String size,
    String style,
    long durationMillis,
    String errorMessage
) {
  public boolean success() { return "SUCCESS".equals(status); }
}