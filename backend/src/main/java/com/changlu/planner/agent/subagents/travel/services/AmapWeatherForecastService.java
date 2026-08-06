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

/** Normalizes Amap's four-day city forecast into the travel weather contract. */
public final class AmapWeatherForecastService implements WeatherForecastService {
  private final TravelApiClient http;
  private final String key;
  private final String baseUrl;

  public AmapWeatherForecastService(TravelApiClient http) {
    this(http, EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", ""),
        EnvironmentConfig.value("AMAP_API_BASE_URL", "amap.api.base-url", "https://restapi.amap.com/v3"));
  }

  public AmapWeatherForecastService(TravelApiClient http, String key, String baseUrl) {
    this.http = http;
    this.key = key;
    this.baseUrl = baseUrl;
  }

  @Override public JsonObject forecast(JsonObject request, String traceId) throws Exception {
    requireConfiguration();
    String adcode = resolveAdcode(request, traceId);
    URI uri = URI.create(baseUrl + "/weather/weatherInfo?key=" + TravelApiClient.encode(key)
        + "&city=" + TravelApiClient.encode(adcode) + "&extensions=all");
    JsonObject body = http.getJson("amap", uri, traceId);
    requireSuccess(body);

    JsonArray weather = new JsonArray();
    Instant fetchedAt = Instant.now();
    for (JsonElement forecast : array(body, "forecasts")) {
      if (!forecast.isJsonObject()) continue;
      for (JsonElement cast : array(forecast.getAsJsonObject(), "casts")) {
        if (!cast.isJsonObject()) continue;
        JsonObject source = cast.getAsJsonObject();
        JsonObject item = new JsonObject();
        String date = text(source, "date");
        item.addProperty("date", date);
        item.addProperty("condition", condition(source));
        numberOrNull(item, "tempHigh", source, "daytemp_float", "daytemp");
        numberOrNull(item, "tempLow", source, "nighttemp_float", "nighttemp");
        // Amap's weather API does not return precipitation, humidity, wind speed, or alerts.
        item.add("precipitationMm", null);
        item.add("precipitationProbability", null);
        item.add("humidityPercent", null);
        item.add("windKmh", null);
        item.add("warnings", new JsonArray());
        item.addProperty("forecastConfidence", confidence(date));
        item.addProperty("provider", "amap");
        item.addProperty("fetchedAt", fetchedAt.toString());
        weather.add(item);
      }
    }
    JsonObject result = new JsonObject();
    result.add("weather", weather);
    result.addProperty("locationId", adcode);
    return result;
  }

  private String resolveAdcode(JsonObject request, String traceId) throws Exception {
    String adcode = text(request, "destinationAdcode");
    if (!adcode.isBlank()) return adcode;
    String destination = text(request, "destination");
    if (destination.isBlank()) destination = text(request, "destinationCity");
    if (!destination.isBlank()) {
      JsonObject geocoded = http.getJson("amap", URI.create(baseUrl + "/geocode/geo?key="
          + TravelApiClient.encode(key) + "&address=" + TravelApiClient.encode(destination)), traceId);
      requireSuccess(geocoded);
      JsonArray geocodes = array(geocoded, "geocodes");
      if (!geocodes.isEmpty() && geocodes.get(0).isJsonObject()) {
        adcode = text(geocodes.get(0).getAsJsonObject(), "adcode");
        if (!adcode.isBlank()) return adcode;
      }
    }
    if (request.has("destinationLng") && request.has("destinationLat")) {
      String location = request.get("destinationLng").getAsString() + "," + request.get("destinationLat").getAsString();
      JsonObject reverse = http.getJson("amap", URI.create(baseUrl + "/geocode/regeo?key="
          + TravelApiClient.encode(key) + "&location=" + TravelApiClient.encode(location)
          + "&extensions=base"), traceId);
      requireSuccess(reverse);
      JsonObject component = object(object(reverse, "regeocode"), "addressComponent");
      adcode = text(component, "adcode");
      if (!adcode.isBlank()) return adcode;
    }
    throw new IllegalStateException("AMAP_WEATHER_LOCATION_NOT_FOUND");
  }

  private void requireConfiguration() {
    if (key == null || key.isBlank()) throw new IllegalStateException("AMAP_API_KEY_NOT_CONFIGURED");
    if (baseUrl == null || baseUrl.isBlank()) throw new IllegalStateException("AMAP_API_BASE_URL_NOT_CONFIGURED");
  }

  private void requireSuccess(JsonObject response) {
    if (!"1".equals(text(response, "status"))) {
      String infoCode = text(response, "infocode");
      throw new IllegalStateException("AMAP_WEATHER_API_ERROR" + (infoCode.isBlank() ? "" : ":" + infoCode));
    }
  }

  private String condition(JsonObject source) {
    String day = text(source, "dayweather");
    String night = text(source, "nightweather");
    if (day.isBlank()) return night;
    return night.isBlank() || night.equals(day) ? day : day + " / " + night;
  }

  private String confidence(String date) {
    try {
      long days = ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(date));
      return days <= 3 ? "high" : "medium";
    } catch (RuntimeException ignored) {
      return "low";
    }
  }

  private void numberOrNull(JsonObject target, String name, JsonObject source, String preferred, String fallback) {
    String value = text(source, preferred);
    if (value.isBlank()) value = text(source, fallback);
    try {
      if (value.isBlank()) target.add(name, null); else target.addProperty(name, Double.parseDouble(value));
    } catch (NumberFormatException error) {
      target.add(name, null);
    }
  }

  private JsonArray array(JsonObject value, String name) {
    return value != null && value.has(name) && value.get(name).isJsonArray() ? value.getAsJsonArray(name) : new JsonArray();
  }

  private JsonObject object(JsonObject value, String name) {
    return value != null && value.has(name) && value.get(name).isJsonObject() ? value.getAsJsonObject(name) : new JsonObject();
  }

  private String text(JsonObject value, String name) {
    JsonElement element = value == null ? null : value.get(name);
    return element == null || element.isJsonNull() ? "" : element.getAsString().trim();
  }
}
