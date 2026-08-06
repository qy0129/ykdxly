package com.changlu.planner.agent.subagents.travel.services;

import com.google.gson.JsonObject;

@FunctionalInterface
public interface WeatherForecastService {
  JsonObject forecast(JsonObject request, String traceId) throws Exception;
}
