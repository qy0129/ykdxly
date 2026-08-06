package com.changlu.planner.agent.subagents.travel.services;

import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;

public final class AmapAttractionResearchService implements AttractionResearchService {
  private final TravelApiClient http;
  private final String key;
  private final String baseUrl;
  public AmapAttractionResearchService(TravelApiClient http) {
    this(http, EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", ""),
        EnvironmentConfig.value("AMAP_API_BASE_URL", "amap.api.base-url", "https://restapi.amap.com/v3"));
  }
  public AmapAttractionResearchService(TravelApiClient http, String key, String baseUrl) { this.http = http; this.key = key; this.baseUrl = baseUrl; }

  @Override public JsonObject research(JsonObject request, String traceId) throws Exception {
    if (key == null || key.isBlank()) throw new IllegalStateException("AMAP_API_KEY_NOT_CONFIGURED");
    String destination = text(request, "destination");
    String requestedAttraction = text(request, "attractionQuery");
    String keywords = !requestedAttraction.isBlank() ? requestedAttraction
        : Boolean.TRUE.equals(bool(request, "beachPreference")) ? "景点 海滩 海滨" : "景点";
    URI uri = URI.create(baseUrl + "/place/text?key=" + TravelApiClient.encode(key) + "&city="
        + TravelApiClient.encode(destination) + "&keywords=" + TravelApiClient.encode(keywords)
        + "&types=110000&citylimit=true&offset=20&page=1&extensions=all");
    JsonObject body = http.getJson("amap", uri, traceId);
    JsonArray attractions = new JsonArray();
    for (JsonElement element : array(body, "pois")) {
      JsonObject poi = element.getAsJsonObject();
      JsonObject item = new JsonObject();
      item.addProperty("attractionId", "amap:" + text(poi, "id")); item.addProperty("name", text(poi, "name"));
      item.addProperty("address", text(poi, "address"));
      String[] coordinate = text(poi, "location").split(",");
      if (coordinate.length == 2) { item.addProperty("lng", Double.parseDouble(coordinate[0])); item.addProperty("lat", Double.parseDouble(coordinate[1])); }
      else { item.add("lng", null); item.add("lat", null); }
      item.addProperty("coordinateSystem", "GCJ02"); item.add("ticketPrice", null);
      item.addProperty("openingHours", ""); item.add("requiresReservation", null);
      item.addProperty("provider", "amap"); item.addProperty("sourceUrl", "https://www.amap.com/");
      item.addProperty("sourceDomain", "amap.com"); item.addProperty("fetchedAt", Instant.now().toString());
      item.addProperty("sourceQuality", "verified"); item.addProperty("evidenceText", text(poi, "type"));
      attractions.add(item);
    }
    JsonObject data = new JsonObject(); data.add("attractions", attractions); return data;
  }
  private Boolean bool(JsonObject value, String name) { JsonElement e = value.get(name); return e == null || e.isJsonNull() ? null : e.getAsBoolean(); }
  private JsonArray array(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray(); }
  private String text(JsonObject value, String name) { JsonElement e = value == null ? null : value.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
}
