package com.changlu.planner.agent.subagents.travel.tools.support;

import com.changlu.planner.shared.config.EnvironmentConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

/** Small Amap adapter used only by Travel Tools. */
public class AmapClient {
  private static final String DISTRICT_URL = "https://restapi.amap.com/v3/config/district";
  private static final String WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
  private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
  private static final String DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";

  private final HttpClient http;
  private final String apiKey;

  public AmapClient() {
    this(EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", ""));
  }

  public AmapClient(String apiKey) {
    this.apiKey = apiKey == null ? "" : apiKey.trim();
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
  }

  public boolean configured() {
    return !apiKey.isBlank();
  }

  public JsonObject weather(String location) throws Exception {
    JsonObject district = get(DISTRICT_URL + "?key=" + encode(apiKey) + "&keywords=" + encode(location)
        + "&subdistrict=0&extensions=base");
    JsonArray districts = array(district, "districts");
    if (districts.isEmpty()) throw new IllegalArgumentException("AMAP_LOCATION_NOT_FOUND:" + location);
    JsonObject match = districts.get(0).getAsJsonObject();
    String adcode = text(match, "adcode");
    if (adcode.isBlank()) throw new IllegalStateException("AMAP_ADCODE_MISSING:" + location);

    JsonObject raw = get(WEATHER_URL + "?key=" + encode(apiKey) + "&city=" + encode(adcode)
        + "&extensions=all");
    JsonObject result = new JsonObject();
    result.addProperty("location", location);
    result.addProperty("adcode", adcode);
    result.addProperty("queriedAt", Instant.now().toString());
    JsonArray forecasts = array(raw, "forecasts");
    JsonArray days = new JsonArray();
    if (!forecasts.isEmpty()) {
      JsonObject forecast = forecasts.get(0).getAsJsonObject();
      result.addProperty("city", text(forecast, "city"));
      for (JsonElement element : array(forecast, "casts")) {
        JsonObject cast = element.getAsJsonObject();
        JsonObject day = new JsonObject();
        day.addProperty("date", text(cast, "date"));
        day.addProperty("dayWeather", text(cast, "dayweather"));
        day.addProperty("nightWeather", text(cast, "nightweather"));
        day.addProperty("dayTemperature", text(cast, "daytemp"));
        day.addProperty("nightTemperature", text(cast, "nighttemp"));
        day.addProperty("dayWind", text(cast, "daywind"));
        day.addProperty("nightWind", text(cast, "nightwind"));
        days.add(day);
      }
    }
    result.add("forecasts", days);
    return result;
  }

  public JsonObject route(String originName, String destinationName, String city) throws Exception {
    Point origin = geocode(originName, city);
    Point destination = geocode(destinationName, city);
    JsonObject raw = get(DRIVING_URL + "?key=" + encode(apiKey) + "&origin=" + encode(origin.location())
        + "&destination=" + encode(destination.location()) + "&extensions=base");
    JsonObject route = object(raw, "route");
    JsonArray paths = array(route, "paths");
    if (paths.isEmpty()) throw new IllegalStateException("AMAP_ROUTE_NOT_FOUND");
    JsonObject path = paths.get(0).getAsJsonObject();
    JsonObject result = new JsonObject();
    result.addProperty("origin", originName);
    result.addProperty("destination", destinationName);
    result.addProperty("originCoordinate", origin.location());
    result.addProperty("destinationCoordinate", destination.location());
    result.addProperty("distanceMeters", integer(path, "distance"));
    result.addProperty("durationMinutes", (int) Math.ceil(integer(path, "duration") / 60.0));
    result.addProperty("tolls", decimal(path, "tolls"));
    result.addProperty("mode", "driving");
    result.addProperty("estimated", true);
    result.addProperty("queriedAt", Instant.now().toString());
    return result;
  }

  private Point geocode(String address, String city) throws Exception {
    String url = GEOCODE_URL + "?key=" + encode(apiKey) + "&address=" + encode(address);
    if (city != null && !city.isBlank()) url += "&city=" + encode(city);
    JsonArray geocodes = array(get(url), "geocodes");
    if (geocodes.isEmpty()) throw new IllegalArgumentException("AMAP_ADDRESS_NOT_FOUND:" + address);
    JsonObject match = geocodes.get(0).getAsJsonObject();
    String location = text(match, "location");
    if (location.isBlank()) throw new IllegalStateException("AMAP_COORDINATE_MISSING:" + address);
    return new Point(location);
  }

  private JsonObject get(String url) throws Exception {
    if (!configured()) throw new IllegalStateException("AMAP_API_KEY_NOT_CONFIGURED");
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        .header("User-Agent", "ChangluPlanner/1.0").GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IllegalStateException("AMAP_HTTP_" + response.statusCode());
    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
    if (!"1".equals(text(json, "status"))) {
      throw new IllegalStateException("AMAP_" + text(json, "infocode") + ":" + text(json, "info"));
    }
    return json;
  }

  private JsonArray array(JsonObject object, String name) {
    return object != null && object.has(name) && object.get(name).isJsonArray()
        ? object.getAsJsonArray(name) : new JsonArray();
  }

  private JsonObject object(JsonObject object, String name) {
    return object != null && object.has(name) && object.get(name).isJsonObject()
        ? object.getAsJsonObject(name) : new JsonObject();
  }

  private String text(JsonObject object, String name) {
    JsonElement value = object == null ? null : object.get(name);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }

  private int integer(JsonObject object, String name) {
    try { return Integer.parseInt(text(object, name)); }
    catch (NumberFormatException error) { return 0; }
  }

  private double decimal(JsonObject object, String name) {
    try { return Double.parseDouble(text(object, name)); }
    catch (NumberFormatException error) { return 0; }
  }

  private String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private record Point(String location) {}
}
