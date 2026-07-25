package com.example.ilink.feature.weather;

import com.example.ilink.config.Config;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 高德行政区与天气接口，作为 Open-Meteo 在国内网络环境下的回退。 */
final class AmapWeatherService {

    private static final String DISTRICT_API_URL = "https://restapi.amap.com/v3/config/district";
    private static final String WEATHER_API_URL = "https://restapi.amap.com/v3/weather/weatherInfo";
    private static final Pattern WIND_FORCE = Pattern.compile("(\\d+)");

    private final HttpClient httpClient;
    private final String apiKey;
    private final Map<String, String> adcodeCache = new ConcurrentHashMap<>();

    AmapWeatherService(HttpClient httpClient) {
        this(httpClient, Config.AMAP_API_KEY);
    }

    AmapWeatherService(HttpClient httpClient, String apiKey) {
        this.httpClient = httpClient;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
    }

    boolean isConfigured() {
        return !apiKey.isBlank();
    }

    List<WeatherLocation> searchLocations(String locationName) throws IOException, InterruptedException {
        if (!isConfigured() || locationName == null || locationName.isBlank()) return List.of();
        List<District> districts = searchDistricts(locationName.trim());
        List<WeatherLocation> locations = new ArrayList<>();
        for (District district : districts) {
            WeatherLocation location = district.toWeatherLocation();
            locations.add(location);
            adcodeCache.put(locationKey(location), district.adcode());
        }
        return locations;
    }

    String queryWeather(WeatherLocation location, LocalDate targetDate, String period)
            throws IOException, InterruptedException {
        AmapWeather weather = loadWeather(location, targetDate, period);
        return formatWeather(location, targetDate, period, weather);
    }

    WeatherSnapshot queryWeatherSnapshot(WeatherLocation location)
            throws IOException, InterruptedException {
        AmapWeather weather = loadWeather(location, LocalDate.now(), "day");
        String text = formatWeather(location, LocalDate.now(), "day", weather);
        String condition = weather.condition();
        return new WeatherSnapshot(text, new WeatherVisualState(
                weatherCode(condition),
                conditionGroup(condition),
                condition,
                isDaytime(),
                cloudCover(condition),
                0,
                0,
                windSpeed(weather.windPower()),
                windDirection(weather.windDirection()),
                weather.temperature(),
                weather.temperature(),
                "Asia/Shanghai"));
    }

    private AmapWeather loadWeather(WeatherLocation location, LocalDate targetDate, String period)
            throws IOException, InterruptedException {
        District district = resolveDistrict(location);
        JsonObject forecastResponse = getJson(WEATHER_API_URL, Map.of(
                "city", district.adcode(), "extensions", "all"));
        JsonObject forecast = firstObject(forecastResponse, "forecasts");
        JsonObject cast = findCast(forecast, targetDate);
        if (cast == null) {
            throw new IOException("高德天气仅返回未来四天预报，暂不包含 " + targetDate);
        }

        boolean current = targetDate.equals(LocalDate.now()) && "day".equals(period);
        JsonObject live = null;
        if (current) {
            JsonObject liveResponse = getJson(WEATHER_API_URL, Map.of(
                    "city", district.adcode(), "extensions", "base"));
            live = firstObject(liveResponse, "lives");
        }

        boolean night = "evening".equals(period);
        String condition = current ? requiredString(live, "weather")
                : requiredString(cast, night ? "nightweather" : "dayweather");
        double temperature = current ? requiredDouble(live, "temperature")
                : requiredDouble(cast, night ? "nighttemp" : "daytemp");
        String windDirection = current ? optionalString(live, "winddirection")
                : optionalString(cast, night ? "nightwind" : "daywind");
        String windPower = current ? optionalString(live, "windpower")
                : optionalString(cast, night ? "nightpower" : "daypower");
        int humidity = current ? (int) Math.round(requiredDouble(live, "humidity")) : -1;
        String reportTime = current ? optionalString(live, "reporttime")
                : optionalString(forecast, "reporttime");
        return new AmapWeather(condition, temperature,
                requiredDouble(cast, "nighttemp"), requiredDouble(cast, "daytemp"),
                humidity, windDirection, windPower, reportTime, current);
    }

    private District resolveDistrict(WeatherLocation location) throws IOException, InterruptedException {
        String cachedAdcode = adcodeCache.get(locationKey(location));
        if (cachedAdcode != null) {
            return new District(location.name(), cachedAdcode, location.longitude(), location.latitude(), "city");
        }
        List<District> districts = searchDistricts(location.name());
        if (districts.isEmpty() && location.admin1() != null && !location.admin1().isBlank()) {
            districts = searchDistricts(location.admin1() + location.name());
        }
        if (districts.isEmpty()) throw new IOException("高德没有找到天气地点：" + location.name());
        District district = districts.getFirst();
        adcodeCache.put(locationKey(location), district.adcode());
        return district;
    }

    private List<District> searchDistricts(String keyword) throws IOException, InterruptedException {
        JsonObject response = getJson(DISTRICT_API_URL, Map.of(
                "keywords", keyword, "subdistrict", "0", "extensions", "base"));
        JsonArray districts = response.getAsJsonArray("districts");
        if (districts == null) return List.of();
        List<District> values = new ArrayList<>();
        for (JsonElement element : districts) {
            JsonObject item = element.getAsJsonObject();
            String center = optionalString(item, "center");
            String adcode = optionalString(item, "adcode");
            if (center.isBlank() || adcode.isBlank()) continue;
            String[] coordinates = center.split(",");
            if (coordinates.length != 2) continue;
            values.add(new District(requiredString(item, "name"), adcode,
                    Double.parseDouble(coordinates[0]), Double.parseDouble(coordinates[1]),
                    optionalString(item, "level")));
        }
        return values;
    }

    private String formatWeather(WeatherLocation location, LocalDate targetDate,
                                 String period, AmapWeather weather) {
        int dayOffset = (int) (targetDate.toEpochDay() - LocalDate.now().toEpochDay());
        StringBuilder text = new StringBuilder(location.displayName())
                .append(weather.current() ? "当前" : dayName(targetDate, dayOffset))
                .append(periodName(period)).append("天气：").append(weather.condition());
        if (weather.current()) {
            text.append("\n当前温度：").append(formatNumber(weather.temperature())).append("℃");
        }
        if ("day".equals(period)) {
            double min = Math.min(weather.nightTemperature(), weather.dayTemperature());
            double max = Math.max(weather.nightTemperature(), weather.dayTemperature());
            text.append("\n温度：").append(formatNumber(min)).append("℃ 至 ")
                    .append(formatNumber(max)).append("℃");
        } else {
            text.append("\n温度：").append(formatNumber(weather.temperature())).append("℃");
        }
        text.append("\n降水概率：--");
        if (weather.humidity() >= 0) text.append("\n湿度：").append(weather.humidity()).append('%');
        if (!weather.windDirection().isBlank() || !weather.windPower().isBlank()) {
            text.append(weather.humidity() >= 0 ? "，" : "\n")
                    .append(weather.windDirection()).append("风")
                    .append(weather.windPower()).append("级");
        }
        String reportTime = weather.reportTime().isBlank()
                ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                : weather.reportTime();
        return text.append("\n数据更新时间：").append(reportTime)
                .append("（当地时间）\n来源：高德开放平台").toString();
    }

    private JsonObject getJson(String endpoint, Map<String, String> parameters)
            throws IOException, InterruptedException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("key", apiKey);
        query.putAll(parameters);
        StringBuilder url = new StringBuilder(endpoint).append('?');
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (url.charAt(url.length() - 1) != '?') url.append('&');
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("高德天气请求失败，HTTP " + response.statusCode());
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"1".equals(optionalString(json, "status"))) {
            throw new IOException("高德天气请求失败：" + optionalString(json, "info"));
        }
        return json;
    }

    private JsonObject findCast(JsonObject forecast, LocalDate targetDate) {
        if (forecast == null) return null;
        JsonArray casts = forecast.getAsJsonArray("casts");
        if (casts == null) return null;
        for (JsonElement element : casts) {
            JsonObject cast = element.getAsJsonObject();
            if (targetDate.toString().equals(optionalString(cast, "date"))) return cast;
        }
        return null;
    }

    private JsonObject firstObject(JsonObject object, String name) throws IOException {
        JsonArray values = object == null ? null : object.getAsJsonArray(name);
        if (values == null || values.isEmpty()) throw new IOException("高德天气返回数据为空");
        return values.get(0).getAsJsonObject();
    }

    private String requiredString(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value.isBlank()) throw new IOException("高德天气缺少字段：" + name);
        return value;
    }

    private double requiredDouble(JsonObject object, String name) throws IOException {
        String value = requiredString(object, name);
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            throw new IOException("高德天气字段格式错误：" + name, error);
        }
    }

    private String optionalString(JsonObject object, String name) {
        JsonElement value = object == null ? null : object.get(name);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private String locationKey(WeatherLocation location) {
        return location.name() + '|' + location.longitude() + '|' + location.latitude();
    }

    private String dayName(LocalDate date, int dayOffset) {
        if (dayOffset == 0) return "今天";
        if (dayOffset == 1) return "明天";
        if (dayOffset == 2) return "后天";
        return date.format(DateTimeFormatter.ofPattern("M月d日"));
    }

    private String periodName(String period) {
        return switch (period) {
            case "morning" -> "上午";
            case "afternoon" -> "下午";
            case "evening" -> "晚上";
            default -> "";
        };
    }

    private String conditionGroup(String condition) {
        if (condition.contains("雷")) return "storm";
        if (condition.contains("雪")) return "snow";
        if (condition.contains("雨")) return "rain";
        if (condition.matches(".*(雾|霾|沙|尘).*")) return "fog";
        if (condition.contains("晴")) return "clear";
        if (condition.matches(".*(云|阴).*")) return "cloudy";
        return "unknown";
    }

    private int weatherCode(String condition) {
        return switch (conditionGroup(condition)) {
            case "storm" -> 95;
            case "snow" -> condition.contains("大") ? 75 : condition.contains("中") ? 73 : 71;
            case "rain" -> condition.contains("大") || condition.contains("暴") ? 65
                    : condition.contains("中") ? 63 : 61;
            case "fog" -> 45;
            case "clear" -> 0;
            case "cloudy" -> condition.contains("阴") ? 3 : 2;
            default -> -1;
        };
    }

    private double cloudCover(String condition) {
        return switch (conditionGroup(condition)) {
            case "clear" -> 0.1;
            case "cloudy" -> condition.contains("阴") ? 0.95 : 0.65;
            case "fog", "rain", "snow", "storm" -> 0.9;
            default -> 0.45;
        };
    }

    private double windSpeed(String windPower) {
        Matcher matcher = WIND_FORCE.matcher(windPower == null ? "" : windPower);
        int force = 0;
        while (matcher.find()) force = Math.max(force, Integer.parseInt(matcher.group(1)));
        return switch (Math.min(force, 12)) {
            case 0 -> 1;
            case 1 -> 4;
            case 2 -> 9;
            case 3 -> 15;
            case 4 -> 24;
            case 5 -> 34;
            case 6 -> 45;
            case 7 -> 56;
            case 8 -> 68;
            case 9 -> 82;
            case 10 -> 96;
            case 11 -> 110;
            default -> 120;
        };
    }

    private double windDirection(String direction) {
        if (direction == null) return 0;
        if (direction.contains("东北")) return 45;
        if (direction.contains("东南")) return 135;
        if (direction.contains("西南")) return 225;
        if (direction.contains("西北")) return 315;
        if (direction.contains("东")) return 90;
        if (direction.contains("南")) return 180;
        if (direction.contains("西")) return 270;
        return 0;
    }

    private boolean isDaytime() {
        int hour = LocalDateTime.now().getHour();
        return hour >= 6 && hour < 18;
    }

    private String formatNumber(double value) {
        return Math.rint(value) == value ? String.valueOf((int) value) : String.format("%.1f", value);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record District(String name, String adcode, double longitude, double latitude, String level) {
        WeatherLocation toWeatherLocation() {
            int priority = switch (level) {
                case "country" -> 100;
                case "province" -> 90;
                case "city" -> 80;
                case "district" -> 70;
                default -> 10;
            };
            return new WeatherLocation(name, "", "", "中国", latitude, longitude, priority, 0);
        }
    }

    private record AmapWeather(String condition, double temperature,
                               double nightTemperature, double dayTemperature,
                               int humidity, String windDirection, String windPower,
                               String reportTime, boolean current) { }
}
