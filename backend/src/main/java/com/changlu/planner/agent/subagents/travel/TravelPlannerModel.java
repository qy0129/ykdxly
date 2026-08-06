package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.JsonObject;

@FunctionalInterface
public interface TravelPlannerModel {
  JsonObject plan(SubagentRequest request, JsonObject facts, String sharedContext) throws Exception;
}
