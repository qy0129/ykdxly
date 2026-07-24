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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
                    item.get("longitude").getAsDouble(),
                    featureCodePriority(item),
                    optInt(item, "population", 0)));
        }
        rankLocations(locations);
        return locations;
    }

    /** 行政中心优先，其次按人口排序，保证明显的主要城市排在同名乡镇之前。 */
    static void rankLocations(List<WeatherLocation> locations) {
        locations.sort((left, right) -> {
            int priority = Integer.compare(right.featurePriority(), left.featurePriority());
            return priority != 0 ? priority : Integer.compare(right.population(), left.population());
        });
    }

    public static WeatherLocation clearlyPrimary(List<WeatherLocation> locations) {
        if (locations == null || locations.isEmpty()) return null;
        WeatherLocation best = locations.getFirst();
        return best.isClearlyPrimary() ? best : null;
    }

    private int featureCodePriority(JsonObject item) {
        String code = optionalString(item, "feature_code");
        if (code == null || code.isBlank()) return 0;
        return switch (code) {
            case "PPLC" -> 100;
            case "PPLA" -> 90;
            case "PPLA2" -> 80;
            case "PPLA3" -> 70;
            case "PPLA4" -> 60;
            case "PPL" -> 10;
            case "PPLX" -> 5;
            default -> 1;
        };
    }

    private int optInt(JsonObject object, String name, int fallback) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    /**
     * 查询指定地点的天气。
     *
     * @param dayOffset 0 表示今天，1 表示明天
     */
    public String queryWeather(WeatherLocation location, int dayOffset)
            throws IOException, InterruptedException {
        return queryWeather(location, dayOffset, "day");
    }

    /** 查询指定地点某一天的全天或上午、下午、晚上天气。 */
    public String queryWeather(WeatherLocation location, int dayOffset, String period)
            throws IOException, InterruptedException {
        return queryWeather(location, LocalDate.now().plusDays(dayOffset), period);
    }

    /** 查询今天起未来七天内的指定日期天气。 */
    public String queryWeather(WeatherLocation location, LocalDate targetDate, String period)
            throws IOException, InterruptedException {
        int dayOffset = Math.toIntExact(ChronoUnit.DAYS.between(LocalDate.now(), targetDate));
        if (dayOffset < 0 || dayOffset > 6) throw new IllegalArgumentException("仅支持查询未来七天内的天气");

        URI uri = URI.create(FORECAST_API_URL
                + "?latitude=" + location.latitude()
                + "&longitude=" + location.longitude()
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m"
                + "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&forecast_days=7&timezone=auto");
        JsonObject response = getJson(uri);
        if (!"day".equals(period)) {
            return appendMetadata(buildPeriodWeather(location, response, dayOffset, targetDate, period), response);
        }
        JsonObject daily = response.getAsJsonObject("daily");

        String dayName = dayName(targetDate, dayOffset);
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
        return appendMetadata(reply.toString(), response);
    }

    /** 把小时预报汇总为一个易读的时段天气结果。 */
    private String buildPeriodWeather(WeatherLocation location, JsonObject response,
                                      int dayOffset, LocalDate targetDate, String period) {
        int startHour = switch (period) {
            case "morning" -> 6;
            case "afternoon" -> 12;
            case "evening" -> 18;
            default -> 0;
        };
        int endHour = "day".equals(period) ? 24 : Math.min(24, startHour + 6);
        int startIndex = dayOffset * 24 + startHour;
        int endIndex = dayOffset * 24 + endHour;
        JsonObject hourly = response.getAsJsonObject("hourly");
        JsonArray temperatures = hourly.getAsJsonArray("temperature_2m");
        JsonArray rainProbabilities = hourly.getAsJsonArray("precipitation_probability");
        JsonArray weatherCodes = hourly.getAsJsonArray("weather_code");
        JsonArray windSpeeds = hourly.getAsJsonArray("wind_speed_10m");

        double minTemperature = Double.MAX_VALUE;
        double maxTemperature = -Double.MAX_VALUE;
        double maxWindSpeed = 0;
        int maxRainProbability = 0;
        int representativeCode = weatherCodes.get(startIndex).getAsInt();
        for (int index = startIndex; index < endIndex; index++) {
            double temperature = temperatures.get(index).getAsDouble();
            int rainProbability = rainProbabilities.get(index).getAsInt();
            minTemperature = Math.min(minTemperature, temperature);
            maxTemperature = Math.max(maxTemperature, temperature);
            maxWindSpeed = Math.max(maxWindSpeed, windSpeeds.get(index).getAsDouble());
            if (rainProbability >= maxRainProbability) {
                maxRainProbability = rainProbability;
                representativeCode = weatherCodes.get(index).getAsInt();
            }
        }

        String dayName = dayName(targetDate, dayOffset);
        String periodName = switch (period) {
            case "morning" -> "上午";
            case "afternoon" -> "下午";
            case "evening" -> "晚上";
            default -> "全天";
        };
        return new StringBuilder(location.displayName()).append(dayName).append(periodName)
                .append("天气：").append(weatherDescription(representativeCode))
                .append("\n温度：").append(formatNumber(minTemperature)).append("℃ 至 ")
                .append(formatNumber(maxTemperature)).append("℃")
                .append("\n最高降水概率：").append(maxRainProbability).append('%')
                .append("，最大风速：").append(formatNumber(maxWindSpeed)).append(" km/h")
                .toString();
    }

    /** 从路由字段中解析今天或明天。 */
    public static int dayOffset(String weatherDay) {
        return weatherDay != null && weatherDay.startsWith("tomorrow") ? 1 : 0;
    }

    /** 从路由或一级路由保存的日期字段中还原目标日期。 */
    public static LocalDate date(String weatherDay) {
        if (weatherDay == null || weatherDay.isBlank()) return LocalDate.now();
        String value = weatherDay.contains("_") ? weatherDay.substring(0, weatherDay.indexOf('_')) : weatherDay;
        if (value.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(value);
        return LocalDate.now().plusDays(dayOffset(weatherDay));
    }

    /** 从路由字段中解析上午、下午、晚上；没有时段时返回全天。 */
    public static String period(String weatherDay) {
        if (weatherDay == null) return "day";
        int separator = weatherDay.indexOf('_');
        return separator < 0 ? "day" : weatherDay.substring(separator + 1);
    }

    private String appendMetadata(String text, JsonObject response) {
        JsonObject current = response.getAsJsonObject("current");
        String updatedAt = current == null ? "" : optionalString(current, "time");
        if (updatedAt.isBlank()) updatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return text + "\n数据更新时间：" + updatedAt + "（当地时间）\n来源：Open-Meteo";
    }

    private String dayName(LocalDate targetDate, int dayOffset) {
        if (dayOffset == 0) return "今天";
        if (dayOffset == 1) return "明天";
        if (dayOffset == 2) return "后天";
        return targetDate.format(DateTimeFormatter.ofPattern("M月d日"));
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
