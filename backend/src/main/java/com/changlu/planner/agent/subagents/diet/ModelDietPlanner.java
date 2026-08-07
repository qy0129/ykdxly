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
                                   List<String> missingFields, String sharedContext) throws Exception {
    return planWithContext(request, sources, dailyTargets, missingFields, sharedContext,
        "", new JsonArray());
  }

  @Override public JsonObject planWithContext(DietRequest request, JsonArray sources,
                                              JsonObject dailyTargets, List<String> missingFields,
                                              String sharedContext, String userRequest,
                                              JsonArray previousMealPlan) throws Exception {
    return model.completeJson("diet-subagent",
        DietPrompt.messages(requestMessage(request, userRequest), gson.toJson(request.toJson()),
            gson.toJson(sources),
            dailyTargets == null ? null : gson.toJson(dailyTargets),
            String.join("、", missingFields),
            sharedContext, previousMealPlan),
        0.15, 5000, 180, 2);
  }

  private String requestMessage(DietRequest request, String userRequest) {
    String goal = request.goal() == null || request.goal().isBlank() ? "健康饮食" : request.goal();
    String base = goal + " 一周饮食计划";
    // 草案修改时把修改文字带进提示词，否则模型只按最初目标生成，改动会被忽略。
    if (userRequest != null && !userRequest.isBlank()) base += "\n用户最新要求：" + userRequest;
    return base;
  }
}
