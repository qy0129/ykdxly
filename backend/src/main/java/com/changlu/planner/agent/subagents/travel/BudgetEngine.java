package com.changlu.planner.agent.subagents.travel;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public final class BudgetEngine {
  public JsonObject estimate(TravelRequest request) {
    int days = days(request); int travelers = request.travelers() == null ? 1 : request.travelers();
    int hotelStar = request.hotelStarRating() == null ? 3 : request.hotelStarRating();
    double accommodationMin = Math.max(0, days - 1) * travelers * (120 + hotelStar * 80);
    double accommodationMax = accommodationMin * 1.8;
    double mealsMin = days * travelers * 100; double mealsMax = days * travelers * 260;
    double localMin = days * travelers * 40; double localMax = days * travelers * 180;
    double activitiesMin = days * travelers * 50; double activitiesMax = days * travelers * 300;
    JsonArray breakdown = new JsonArray();
    add(breakdown, "accommodation", accommodationMin, accommodationMax, "rule-estimate:no-live-hotel-price");
    add(breakdown, "meals", mealsMin, mealsMax, "rule-estimate"); add(breakdown, "localTransit", localMin, localMax, "rule-estimate");
    add(breakdown, "activities", activitiesMin, activitiesMax, "rule-estimate:no-confirmed-ticket-price");
    double minimum = accommodationMin + mealsMin + localMin + activitiesMin;
    double maximum = accommodationMax + mealsMax + localMax + activitiesMax;
    double amount = (minimum + maximum) / 2; double budget = request.budget().has("amount") ? request.budget().get("amount").getAsDouble() : 0;
    JsonObject result = new JsonObject(); result.addProperty("amount", amount); result.addProperty("minimum", minimum); result.addProperty("maximum", maximum);
    result.addProperty("currency", request.budget().has("currency") ? request.budget().get("currency").getAsString() : "CNY");
    result.addProperty("estimated", true); result.addProperty("confidence", "low"); result.add("breakdown", breakdown);
    result.addProperty("overBudget", budget > 0 && minimum > budget);
    result.addProperty("overBudgetWarning", budget > 0 && minimum > budget ? "最低估算已超过用户预算；交通和酒店缺少实时价格，需再次核实。" : "");
    return result;
  }
  private int days(TravelRequest request) { try { return (int) ChronoUnit.DAYS.between(LocalDate.parse(request.startDate()), LocalDate.parse(request.endDate())) + 1; } catch (Exception e) { return 1; } }
  private void add(JsonArray values, String category, double min, double max, String source) { JsonObject item = new JsonObject(); item.addProperty("category", category); item.addProperty("amount", (min + max) / 2); item.addProperty("minimum", min); item.addProperty("maximum", max); item.addProperty("source", source); values.add(item); }
}
