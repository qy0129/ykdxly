package com.changlu.planner.agent.core.runtime;

import org.slf4j.Logger;

/** Small trace boundary; callers must pass summaries rather than secrets or document bodies. */
public final class TraceRecorder {
  private final Logger log;

  public TraceRecorder(Logger log) { this.log = log; }

  public void event(String traceId, String runId, String component, String event, long durationMs, String summary) {
    log.info("[AgentTrace] trace={} run={} component={} event={} durationMs={} summary={}",
        traceId, runId, component, event, durationMs, sanitize(summary));
  }

  private String sanitize(String value) {
    if (value == null) return "";
    String sanitized = value.replaceAll("(?i)(authorization|token|password|api[_-]?key)\\s*[:=]\\s*\\S+", "$1=[REDACTED]")
        .replaceAll("\\s+", " ").trim();
    return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 237) + "...";
  }
}
