package com.changlu.planner.agent.subagents.image.tools;

/** 文生图调用失败，携带稳定错误码供上层映射用户可读信息。 */
public final class ImageGenerationException extends Exception {
  private final String code;
  private final boolean retryable;

  public ImageGenerationException(String code, String message, boolean retryable) {
    super(message);
    this.code = code;
    this.retryable = retryable;
  }

  public String code() { return code; }
  public boolean retryable() { return retryable; }
}