package com.changlu.planner.agent.subagents.travel.services;

import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public final class QWeatherForecastService implements WeatherForecastService {
  private final TravelApiClient http;
  private final String key;
  private final String weatherBaseUrl;
  private final String geoBaseUrl;

  public QWeatherForecastService(TravelApiClient http) {
    this(http, EnvironmentConfig.value("QWEATHER_API_KEY", "weather.api.key", ""),
        EnvironmentConfig.value("QWEATHER_API_BASE_URL", "weather.api.base-url", ""),
        EnvironmentConfig.value("QWEATHER_GEO_BASE_URL", "weather.geo-base-url", ""));
  }
  public QWeatherForecastService(TravelApiClient http, String key, String weatherBaseUrl, String geoBaseUrl) {
    this.http = http; this.key = key; this.weatherBaseUrl = weatherBaseUrl; this.geoBaseUrl = geoBaseUrl;
  }

  @Override public JsonObject forecast(JsonObject request, String traceId) throws Exception {
    if (key == null || key.isBlank()) throw new IllegalStateException("QWEATHER_API_KEY_NOT_CONFIGURED");
    if (weatherBaseUrl == null || weatherBaseUrl.isBlank() || geoBaseUrl == null || geoBaseUrl.isBlank()) {
      throw new IllegalStateException("QWEATHER_API_HOST_NOT_CONFIGURED");
    }
    String location = text(request, "locationId");
    if (location.isBlank()) location = lookup(request.get("destinationLng").getAsDouble(),
        request.get("destinationLat").getAsDouble(), traceId);
    JsonObject forecast = http.getJson("qweather", URI.create(weatherBaseUrl + "/weather/10d?location="
        + TravelApiClient.encode(location)), traceId, authHeaders());
    JsonArray warnings = warning(location, traceId);
    JsonArray result = new JsonArray();
    LocalDate today = LocalDate.now();
    for (JsonElement element : array(forecast, "daily")) {
      JsonObject row = element.getAsJsonObject();
      JsonObject item = new JsonObject();
      String dateText = text(row, "fxDate");
      item.addProperty("date", dateText); item.addProperty("condition", text(row, "textDay"));
      numberOrNull(item, "tempHigh", row, "tempMax"); numberOrNull(item, "tempLow", row, "tempMin");
      numberOrNull(item, "precipitationMm", row, "precip");
      numberOrNull(item, "precipitationProbability", row, "pop");
      numberOrNull(item, "humidityPercent", row, "humidity"); numberOrNull(item, "windKmh", row, "windSpeedDay");
      item.add("warnings", warnings.deepCopy());
      long days = ChronoUnit.DAYS.between(today, LocalDate.parse(dateText));
      item.addProperty("forecastConfidence", days <= 3 ? "high" : days <= 7 ? "medium" : "low");
      item.addProperty("provider", "qweather"); item.addProperty("fetchedAt", Instant.now().toString());
      result.add(item);
    }
    JsonObject data = new JsonObject(); data.add("weather", result); data.addProperty("locationId", location);
    return data;
  }

  private String lookup(double lng, double lat, String traceId) throws Exception {
    JsonObject body = http.getJson("qweather", URI.create(geoBaseUrl + "/city/lookup?location=" + lng + "," + lat),
        traceId, authHeaders());
    JsonArray locations = array(body, "location");
    if (locations.isEmpty()) throw new IllegalStateException("QWEATHER_LOCATION_NOT_FOUND");
    return text(locations.get(0).getAsJsonObject(), "id");
  }
  private JsonArray warning(String location, String traceId) {
    try {
      JsonObject body = http.getJson("qweather", URI.create(weatherBaseUrl + "/warning/now?location="
          + TravelApiClient.encode(location)), traceId, authHeaders());
      return array(body, "warning");
    } catch (Exception error) { return new JsonArray(); }
  }
  private Map<String, String> authHeaders() { return Map.of("X-QW-Api-Key", key); }
  private void numberOrNull(JsonObject target, String name, JsonObject source, String sourceName) {
    String value = text(source, sourceName);
    if (value.isBlank()) target.add(name, null); else try { target.addProperty(name, Double.parseDouble(value)); }
    catch (NumberFormatException error) { target.add(name, null); }
  }
  private JsonArray array(JsonObject value, String name) { return value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray(); }
  private String text(JsonObject value, String name) { JsonElement e = value == null ? null : value.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString(); }
}
