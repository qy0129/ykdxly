package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@FunctionalInterface
public interface TravelPlannerModel {
  JsonObject plan(SubagentRequest request, JsonArray sources, String sharedContext) throws Exception;
}
