package com.changlu.planner.agent.subagents.travel.refresh;

import com.changlu.planner.agent.subagents.travel.services.AttractionResearchService;
import com.changlu.planner.agent.subagents.travel.services.MapRoutingService;
import com.changlu.planner.agent.subagents.travel.services.WeatherForecastService;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Production adapter that refreshes independent providers without exposing keys or exact coordinates in logs. */
public final class ProductionTravelRefreshProvider implements TravelRefreshProvider {
  private final WeatherForecastService weather;
  private final AttractionResearchService attractions;
  private final MapRoutingService routing;

  public ProductionTravelRefreshProvider(WeatherForecastService weather, AttractionResearchService attractions,
                                         MapRoutingService routing) {
    this.weather = weather; this.attractions = attractions; this.routing = routing;
  }

  @Override public RefreshBatch refresh(TravelRefreshRepository.TravelPlan plan, String traceId) {
    List<Snapshot> snapshots = new ArrayList<>(); List<String> errors = new ArrayList<>();
    JsonObject context = plan.context();
    if (context.has("destinationLat") && context.has("destinationLng")) {
      JsonObject request = new JsonObject();
      request.addProperty("destinationLat", context.get("destinationLat").getAsDouble());
      request.addProperty("destinationLng", context.get("destinationLng").getAsDouble());
      request.addProperty("destination", plan.destination());
      request.addProperty("startDate", plan.departureAt().toString()); request.addProperty("endDate", plan.endAt().toString());
      collect("amap", "weather", Duration.ofHours(6), snapshots, errors, () -> weather.forecast(request, traceId));
    } else errors.add("amap:location_unavailable");

    JsonObject attractionRequest = new JsonObject(); attractionRequest.addProperty("destination", plan.destination());
    collect("amap", "attractions", Duration.ofHours(24), snapshots, errors,
        () -> attractions.research(attractionRequest, traceId));

    JsonArray routes = context.has("routes") && context.get("routes").isJsonArray()
        ? context.getAsJsonArray("routes") : new JsonArray();
    if (!routes.isEmpty()) {
      JsonObject routeRequest = new JsonObject(); routeRequest.add("routes", routes.deepCopy());
      collect("amap", "routing", Duration.ofHours(1), snapshots, errors,
          () -> routing.route(routeRequest, traceId));
    }
    return new RefreshBatch(snapshots, errors);
  }

  private void collect(String provider, String dataType, Duration ttl, List<Snapshot> snapshots,
                       List<String> errors, Request request) {
    try { snapshots.add(new Snapshot(provider, dataType, request.get(), ttl)); }
    catch (Exception error) { errors.add(provider + ":unavailable"); }
  }

  @FunctionalInterface private interface Request { JsonObject get() throws Exception; }
}
