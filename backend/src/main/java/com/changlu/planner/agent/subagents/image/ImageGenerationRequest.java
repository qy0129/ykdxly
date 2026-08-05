package com.changlu.planner.agent.subagents.image;

/** 文生图的规范化请求参数。 */
public record ImageGenerationRequest(
    String prompt,
    String size,
    String style,
    Integer quality
) {
  public ImageGenerationRequest {
    prompt = prompt == null ? "" : prompt.trim();
  }
}