package com.changlu.planner.agent.subagents.travel.services;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface GeocodingService {
  JsonObject resolve(JsonObject request, String traceId) throws Exception;
}
