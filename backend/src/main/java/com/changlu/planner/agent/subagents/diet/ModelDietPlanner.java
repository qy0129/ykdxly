package com.changlu.planner.agent.subagents.diet;

import com.changlu.planner.agent.core.ModelClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;

/** ModelClient.completeJson 实现（与 ModelTravelPlanner 同构）。 */
public final class ModelDietPlanner implements DietPlannerModel {
  private final ModelClient model;
  private final Gson gson = new Gson();

  public ModelDietPlanner(ModelClient model) { this.model = model; }

  @Override public JsonObject plan(DietRequest request, JsonArray sources, JsonObject dailyTargets,
                                   List<String> missingFields) throws Exception {
    return model.completeJson("diet-subagent",
        DietPrompt.messages(requestMessage(request), gson.toJson(request.toJson()),
            gson.toJson(sources),
            dailyTargets == null ? null : gson.toJson(dailyTargets),
            String.join("、", missingFields)),
        0.15, 5000, 180, 2);
  }

  private String requestMessage(DietRequest request) {
    String goal = request.goal() == null || request.goal().isBlank() ? "健康饮食" : request.goal();
    return goal + " 一周饮食计划";
  }
}
