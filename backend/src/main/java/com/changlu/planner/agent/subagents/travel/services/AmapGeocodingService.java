package com.changlu.planner.agent.subagents.travel.services;

import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;

public final class AmapGeocodingService implements GeocodingService {
  private final TravelApiClient http;
  private final String key;
  private final String baseUrl;

  public AmapGeocodingService(TravelApiClient http) {
    this(http, EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", ""),
        EnvironmentConfig.value("AMAP_API_BASE_URL", "amap.api.base-url", "https://restapi.amap.com/v3"));
  }
  public AmapGeocodingService(TravelApiClient http, String key, String baseUrl) {
    this.http = http; this.key = key; this.baseUrl = baseUrl;
  }

  @Override public JsonObject resolve(JsonObject request, String traceId) throws Exception {
    requireKey();
    String destination = text(request, "destination");
    JsonObject destinationGeo = geocode(destination, traceId);
    JsonObject result = new JsonObject();
    result.addProperty("originName", text(request, "origin"));
    result.add("originLat", null); result.add("originLng", null);
    result.addProperty("originInferred", false);
    JsonObject device = object(request, "deviceLocation");
    if (result.get("originName").getAsString().isBlank() && usableDeviceLocation(device)) {
      Gcj02.Point point = Gcj02.fromWgs84(device.get("lat").getAsDouble(), device.get("lng").getAsDouble());
      result.addProperty("originLat", point.lat()); result.addProperty("originLng", point.lng());
      result.addProperty("originName", reverse(point, traceId)); result.addProperty("originInferred", true);
    } else if (!result.get("originName").getAsString().isBlank()) {
      JsonObject originGeo = geocode(result.get("originName").getAsString(), traceId);
      copyCoordinate(originGeo, result, "origin");
    }
    result.addProperty("destinationName", destination);
    copyCoordinate(destinationGeo, result, "destination");
    result.addProperty("destinationAdcode", text(destinationGeo, "adcode"));
    result.addProperty("destinationCity", text(destinationGeo, "city"));
    result.addProperty("destinationAdminArea", text(destinationGeo, "province"));
    result.addProperty("destinationCountry", "中国");
    result.addProperty("coordinateSystem", "GCJ02");
    result.addProperty("timezone", text(device, "timezone").isBlank() ? "Asia/Shanghai" : text(device, "timezone"));
    result.addProperty("provider", "amap"); result.addProperty("fetchedAt", Instant.now().toString());
    return result;
  }

  private JsonObject geocode(String address, String traceId) throws Exception {
    if (address == null || address.isBlank()) return new JsonObject();
    URI uri = URI.create(baseUrl + "/geocode/geo?key=" + TravelApiClient.encode(key)
        + "&address=" + TravelApiClient.encode(address));
    JsonObject body = http.getJson("amap", uri, traceId);
    JsonArray values = body.has("geocodes") ? body.getAsJsonArray("geocodes") : new JsonArray();
    return values.isEmpty() ? new JsonObject() : values.get(0).getAsJsonObject();
  }

  private String reverse(Gcj02.Point point, String traceId) throws Exception {
    URI uri = URI.create(baseUrl + "/geocode/regeo?key=" + TravelApiClient.encode(key)
        + "&location=" + point.lng() + "," + point.lat() + "&extensions=base");
    JsonObject body = http.getJson("amap", uri, traceId);
    JsonObject regeocode = object(body, "regeocode");
    return text(regeocode, "formatted_address");
  }

  private boolean usableDeviceLocation(JsonObject device) {
    if (!"granted".equals(text(device, "permission")) || !device.has("lat") || !device.has("lng")) return false;
    try { return OffsetDateTime.parse(text(device, "capturedAt")).toInstant().isAfter(Instant.now().minusSeconds(600)); }
    catch (Exception error) { return false; }
  }
  private void copyCoordinate(JsonObject source, JsonObject target, String prefix) {
    String[] coordinate = text(source, "location").split(",");
    if (coordinate.length == 2) {
      target.addProperty(prefix + "Lng", Double.parseDouble(coordinate[0]));
      target.addProperty(prefix + "Lat", Double.parseDouble(coordinate[1]));
    }
  }
  private void requireKey() { if (key == null || key.isBlank()) throw new IllegalStateException("AMAP_API_KEY_NOT_CONFIGURED"); }
  private JsonObject object(JsonObject value, String name) { JsonElement e = value.get(name); return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }
  private String text(JsonObject value, String name) { JsonElement e = value == null ? null : value.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
}
