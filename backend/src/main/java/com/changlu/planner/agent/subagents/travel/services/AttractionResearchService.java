package com.changlu.planner.agent.subagents.travel.services;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface AttractionResearchService {
  JsonObject research(JsonObject request, String traceId) throws Exception;
}
