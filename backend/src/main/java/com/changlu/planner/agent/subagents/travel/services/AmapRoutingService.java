package com.changlu.planner.agent.subagents.travel.services;

import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;

public final class AmapRoutingService implements MapRoutingService {
  private final TravelApiClient http; private final String key; private final String baseUrl;
  public AmapRoutingService(TravelApiClient http) {
    this(http, EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", ""),
        EnvironmentConfig.value("AMAP_API_BASE_URL", "amap.api.base-url", "https://restapi.amap.com/v3"));
  }
  public AmapRoutingService(TravelApiClient http, String key, String baseUrl) { this.http = http; this.key = key; this.baseUrl = baseUrl; }
  @Override public JsonObject route(JsonObject request, String traceId) throws Exception {
    if (key == null || key.isBlank()) throw new IllegalStateException("AMAP_API_KEY_NOT_CONFIGURED");
    JsonArray output = new JsonArray();
    for (JsonElement element : array(request, "routes")) {
      JsonObject route = element.getAsJsonObject(); String mode = text(route, "mode");
      if (!java.util.Set.of("walking", "driving", "transit").contains(mode)) mode = "walking";
      String path = mode.equals("transit") ? "/direction/transit/integrated" : "/direction/" + mode;
      URI uri = URI.create(baseUrl + path + "?key=" + TravelApiClient.encode(key) + "&origin="
          + TravelApiClient.encode(text(route, "origin")) + "&destination=" + TravelApiClient.encode(text(route, "destination")));
      JsonObject body = http.getJson("amap", uri, traceId); JsonObject item = route.deepCopy();
      item.add("rawRoute", body.has("route") ? body.get("route").deepCopy() : new JsonObject());
      item.addProperty("coordinateSystem", "GCJ02"); item.addProperty("provider", "amap");
      item.addProperty("fetchedAt", Instant.now().toString()); output.add(item);
    }
    JsonObject data = new JsonObject(); data.add("transitMatrix", output); return data;
  }
  private JsonArray array(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray(); }
  private String text(JsonObject value, String name) { JsonElement e = value == null ? null : value.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
}
