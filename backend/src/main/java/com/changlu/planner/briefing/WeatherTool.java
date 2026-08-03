package com.changlu.planner.briefing;

import com.changlu.planner.config.EnvironmentConfig;
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

/** Weather tool used only by the briefing sub-agent. */
final class WeatherTool {
  private static final String DISTRICT_URL = "https://restapi.amap.com/v3/config/district";
  private static final String WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final String apiKey = EnvironmentConfig.value("AMAP_API_KEY", "amap.api.key", "");
  private final String location = EnvironmentConfig.value("BRIEFING_DEFAULT_LOCATION", "briefing.default.location", "杭州");

  String current() {
    if (apiKey.isBlank() || location.isBlank()) return "";
    try {
      JsonObject district = get(DISTRICT_URL + "?key=" + encode(apiKey) + "&keywords=" + encode(location)
          + "&subdistrict=0&extensions=base");
      JsonArray districts = district.getAsJsonArray("districts");
      if (districts == null || districts.isEmpty()) return "";
      String adcode = text(districts.get(0).getAsJsonObject(), "adcode");
      if (adcode.isBlank()) return "";
      JsonObject weather = get(WEATHER_URL + "?key=" + encode(apiKey) + "&city=" + encode(adcode)
          + "&extensions=base");
      JsonArray lives = weather.getAsJsonArray("lives");
      if (lives == null || lives.isEmpty()) return "";
      JsonObject live = lives.get(0).getAsJsonObject();
      StringBuilder result = new StringBuilder(text(live, "city")).append("当前天气：")
          .append(text(live, "weather"));
      String temperature = text(live, "temperature");
      if (!temperature.isBlank()) result.append("，").append(temperature).append("℃");
      String humidity = text(live, "humidity");
      if (!humidity.isBlank()) result.append("，湿度 ").append(humidity).append('%');
      String wind = text(live, "winddirection");
      String power = text(live, "windpower");
      if (!wind.isBlank() || !power.isBlank()) result.append("，").append(wind).append("风 ").append(power).append("级");
      return result.toString();
    } catch (Exception error) {
      System.err.println("[简报天气工具] 获取失败: " + error.getMessage());
      return "";
    }
  }

  private JsonObject get(String url) throws Exception {
    HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8)).GET().build();
    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    if (response.statusCode() != 200) throw new IllegalStateException("HTTP " + response.statusCode());
    JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
    if (!"1".equals(text(json, "status"))) throw new IllegalStateException(text(json, "info"));
    return json;
  }

  private String text(JsonObject object, String name) {
    JsonElement value = object == null ? null : object.get(name);
    return value == null || value.isJsonNull() ? "" : value.getAsString();
  }

  private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
