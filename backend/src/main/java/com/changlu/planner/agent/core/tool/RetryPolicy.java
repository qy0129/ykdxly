package com.changlu.planner.agent.core.tool;

import java.time.Duration;

public record RetryPolicy(int maxAttempts, Duration initialDelay, double backoffMultiplier) {
  public RetryPolicy {
    if (maxAttempts < 1 || maxAttempts > 3) throw new IllegalArgumentException("retry_attempts_invalid");
    initialDelay = initialDelay == null ? Duration.ZERO : initialDelay;
    if (initialDelay.isNegative()) throw new IllegalArgumentException("retry_delay_invalid");
    if (backoffMultiplier < 1) throw new IllegalArgumentException("retry_backoff_invalid");
  }

  public static RetryPolicy none() { return new RetryPolicy(1, Duration.ZERO, 1); }
  public static RetryPolicy readOnlyNetwork() { return new RetryPolicy(2, Duration.ofMillis(250), 2); }
}
