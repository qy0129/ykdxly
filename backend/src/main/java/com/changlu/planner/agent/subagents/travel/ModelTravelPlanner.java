package com.changlu.planner.agent.subagents.travel;

import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.core.contract.SubagentRequest;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModelTravelPlanner implements TravelPlannerModel {
  private static final Logger LOG = LoggerFactory.getLogger(ModelTravelPlanner.class);
  static final int DEFAULT_MAX_TOKENS = 4800;
  static final int DEFAULT_TIMEOUT_SECONDS = 120;
  private final ModelClient model;
  private final Gson gson = new Gson();
  private final int maxTokens;
  private final int timeoutSeconds;

  public ModelTravelPlanner(ModelClient model) {
    this(model,
        boundedInt(EnvironmentConfig.value("TRAVEL_MODEL_MAX_TOKENS", "travel.model.max-tokens",
            String.valueOf(DEFAULT_MAX_TOKENS)), DEFAULT_MAX_TOKENS, 512, 8000),
        boundedInt(EnvironmentConfig.value("TRAVEL_MODEL_TIMEOUT_SECONDS", "travel.model.timeout-seconds",
            String.valueOf(DEFAULT_TIMEOUT_SECONDS)), DEFAULT_TIMEOUT_SECONDS, 10, 170));
  }

  ModelTravelPlanner(ModelClient model, int maxTokens, int timeoutSeconds) {
    this.model = model;
    this.maxTokens = maxTokens;
    this.timeoutSeconds = timeoutSeconds;
  }

  @Override public JsonObject plan(SubagentRequest request, JsonObject facts, String sharedContext) throws Exception {
    JsonArray messages = TravelPrompt.messages(request.message(), gson.toJson(request.arguments()),
        gson.toJson(facts), sharedContext);
    int inputChars = 0;
    for (JsonElement message : messages) {
      JsonElement content = message.getAsJsonObject().get("content");
      if (content != null && content.isJsonPrimitive()) inputChars += content.getAsString().length();
    }
    LOG.info("[旅行模型请求] inputChars={} maxTokens={} timeoutSeconds={}",
        inputChars, maxTokens, timeoutSeconds);
    long startedAt = System.nanoTime();
    JsonObject result = model.completeJson("travel-subagent", messages,
        0.15, maxTokens, timeoutSeconds, 1);
    LOG.info("[旅行模型完成] durationMs={}", (System.nanoTime() - startedAt) / 1_000_000);
    return result;
  }

  static int boundedInt(String value, int fallback, int minimum, int maximum) {
    try {
      int parsed = Integer.parseInt(value == null ? "" : value.trim());
      return parsed >= minimum && parsed <= maximum ? parsed : fallback;
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }
}
