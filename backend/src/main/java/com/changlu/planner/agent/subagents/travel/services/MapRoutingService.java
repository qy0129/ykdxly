package com.changlu.planner.agent.subagents.travel.services;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface MapRoutingService {
  JsonObject route(JsonObject request, String traceId) throws Exception;
}
