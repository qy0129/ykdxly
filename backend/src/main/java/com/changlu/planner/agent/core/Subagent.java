package com.changlu.planner.agent.core;

import com.google.gson.JsonObject;

public interface Subagent {
  String name();
  String description();
  JsonObject execute(String request, AgentContext context) throws Exception;
}
