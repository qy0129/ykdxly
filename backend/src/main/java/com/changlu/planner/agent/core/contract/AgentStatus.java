package com.changlu.planner.agent.core.contract;

/** Stable result states shared by every standard Subagent. */
public enum AgentStatus {
  COMPLETED,
  WAITING_USER,
  WAITING_CONFIRMATION,
  FAILED,
  CANCELLED;

  public String jsonValue() { return name().toLowerCase(); }
}
