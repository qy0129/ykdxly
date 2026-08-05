package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class ModelTravelPlanner implements TravelPlannerModel {
  private final ModelClient model;
  private final Gson gson = new Gson();

  public ModelTravelPlanner(ModelClient model) { this.model = model; }

  @Override public JsonObject plan(SubagentRequest request, JsonArray sources) throws Exception {
    return model.completeJson("travel-subagent",
        TravelPrompt.messages(request.message(), gson.toJson(request.arguments()), gson.toJson(sources)),
        0.15, 5000, 75, 2);
  }
}
