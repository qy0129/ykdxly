package com.example.ilink.feature.weather;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立天气查询服务。
 *
 * <p>使用 Open-Meteo 的地理编码和天气预报接口，不依赖机器人、模型或 API Key。</p>
 */
public final class WeatherService {

    private static final String GEOCODING_API_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_API_URL = "https://api.open-meteo.com/v1/forecast";

    private final HttpClient httpClient;

    /** 创建可独立使用的天气服务。 */
    public WeatherService() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    /** 使用调用方提供的 HTTP 客户端创建天气服务。 */
    public WeatherService(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * 根据 Open-Meteo 可识别的地点名查询候选地点，用于处理同名乡镇或城市。
     */
    public List<WeatherLocation> searchLocations(String locationName) throws IOException, InterruptedException {
        if (locationName == null || locationName.isBlank()) {
            return List.of();
        }

        String encodedName = URLEncoder.encode(locationName.trim(), StandardCharsets.UTF_8);
        URI uri = URI.create(GEOCODING_API_URL + "?name=" + encodedName
                + "&count=5&language=zh&format=json");
        JsonObject response = getJson(uri);
        JsonArray results = response.getAsJsonArray("results");
        if (results == null) {
            return List.of();
        }

        List<WeatherLocation> locations = new ArrayList<>();
        for (JsonElement element : results) {
            JsonObject item = element.getAsJsonObject();
            locations.add(new WeatherLocation(
                    requiredString(item, "name"),
                    optionalString(item, "admin1"),
                    optionalString(item, "admin2"),
                    optionalString(item, "country"),
                    item.get("latitude").getAsDouble(),
                    item.get("longitude").getAsDouble()));
        }
        return locations;
    }

    /**
     * 查询指定地点的天气。
     *
     * @param dayOffset 0 表示今天，1 表示明天
     */
    public String queryWeather(WeatherLocation location, int dayOffset)
            throws IOException, InterruptedException {
        if (dayOffset < 0 || dayOffset > 1) {
            throw new IllegalArgumentException("仅支持查询今天或明天的天气");
        }

        URI uri = URI.create(FORECAST_API_URL
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=2&timezone=auto");
        JsonObject response = getJson(uri);
        JsonObject daily = response.getAsJsonObject("daily");

        String dayName = dayOffset == 0 ? "今天" : "明天";
        int weatherCode = daily.getAsJsonArray("weather_code").get(dayOffset).getAsInt();
        double maxTemperature = daily.getAsJsonArray("temperature_2m_max").get(dayOffset).getAsDouble();
        double minTemperature = daily.getAsJsonArray("temperature_2m_min").get(dayOffset).getAsDouble();
        int rainProbability = daily.getAsJsonArray("precipitation_probability_max").get(dayOffset).getAsInt();

        StringBuilder reply = new StringBuilder(location.displayName())
                .append(dayName).append("天气：").append(weatherDescription(weatherCode))
                .append("\n温度：").append(formatNumber(minTemperature)).append("℃ 至 ")
                .append(formatNumber(maxTemperature)).append("℃")
                .append("\n降水概率：").append(rainProbability).append('%');

        if (dayOffset == 0) {
            JsonObject current = response.getAsJsonObject("current");
            reply.append("\n当前温度：").append(formatNumber(current.get("temperature_2m").getAsDouble())).append("℃")
                    .append("，体感 ").append(formatNumber(current.get("apparent_temperature").getAsDouble())).append("℃")
                    .append("\n湿度：").append(current.get("relative_humidity_2m").getAsInt()).append('%')
                    .append("，风速：")
                    .append(formatNumber(current.get("wind_speed_10m").getAsDouble())).append(" km/h");
        }
        return reply.toString();
    }

    private JsonObject getJson(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("天气服务请求失败，HTTP " + response.statusCode());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private String requiredString(JsonObject object, String name) {
        return object.get(name).getAsString();
    }

    private String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private String formatNumber(double value) {
        return Math.rint(value) == value ? String.valueOf((int) value) : String.format("%.1f", value);
    }

    private String weatherDescription(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴朗";
            case 2 -> "局部多云";
            case 3 -> "阴";
            case 45, 48 -> "有雾";
            case 51, 53, 55, 56, 57 -> "毛毛雨";
            case 61, 80 -> "小雨";
            case 63, 81 -> "中雨";
            case 65, 82 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71, 77, 85 -> "小雪";
            case 73, 86 -> "中雪";
            case 75 -> "大雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "天气未知";
        };
    }
}
