package com.changlu.planner.agent.subagents.image.tools;

/** 一次生成结果的持久化记录模型。 */
public record ImageRecord(
    String requestId,
    String idempotencyKey,
    String prompt,
    String size,
    String style,
    String status,
    String imageUrl,
    String assetPath,
    String provider,
    String errorMessage,
    String traceId,
    long createdAtMillis
) {
  public boolean success() { return "SUCCESS".equals(status); }
}
