package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);
    private static final String[] WEATHER_CODES = {
            "晴天", "大部晴朗", "局部多云", "多云",
            "阴天", "雾", "轻雾", "冻雾",
            "毛毛雨", "冻毛毛雨", "冻雨", "轻雨",
            "雨", "大雨", "暴雨", "超大暴雨",
            "冰雹", "小冰雹", "雪", "小雪",
            "中雪", "大雪", "暴雪", "雪暴",
            "阵雪", "阵雨", "冻雨", "雷暴",
            "雷暴伴小冰雹", "龙卷风"
    };

    private static final Map<String, double[]> CITY_COORDS = new ConcurrentHashMap<>();

    static {
        CITY_COORDS.put("北京", new double[]{39.9042, 116.4074});
        CITY_COORDS.put("上海", new double[]{31.2304, 121.4737});
        CITY_COORDS.put("广州", new double[]{23.1291, 113.2644});
        CITY_COORDS.put("深圳", new double[]{22.5431, 114.0579});
        CITY_COORDS.put("杭州", new double[]{30.2741, 120.1551});
        CITY_COORDS.put("成都", new double[]{30.5728, 104.0668});
        CITY_COORDS.put("南京", new double[]{32.0603, 118.7969});
        CITY_COORDS.put("武汉", new double[]{30.5928, 114.3055});
        CITY_COORDS.put("重庆", new double[]{29.4316, 106.9123});
        CITY_COORDS.put("天津", new double[]{39.3434, 117.3616});
        CITY_COORDS.put("苏州", new double[]{31.2990, 120.5853});
        CITY_COORDS.put("西安", new double[]{34.3416, 108.9398});
        CITY_COORDS.put("长沙", new double[]{28.2282, 112.9388});
        CITY_COORDS.put("青岛", new double[]{36.0671, 120.3826});
        CITY_COORDS.put("大连", new double[]{38.9140, 121.6147});
        CITY_COORDS.put("厦门", new double[]{24.4798, 118.0894});
        CITY_COORDS.put("宁波", new double[]{29.8683, 121.5440});
        CITY_COORDS.put("福州", new double[]{26.0745, 119.2965});
        CITY_COORDS.put("合肥", new double[]{31.8206, 117.2272});
        CITY_COORDS.put("郑州", new double[]{34.7466, 113.6253});
    }

    private final OkHttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final double defaultLatitude;
    private final double defaultLongitude;
    private final String defaultLocation;

    public WeatherService(
            @Value("${weather.latitude:39.9042}") double defaultLatitude,
            @Value("${weather.longitude:116.4074}") double defaultLongitude,
            @Value("${weather.location:北京}") String defaultLocation) {
        this.defaultLatitude = defaultLatitude;
        this.defaultLongitude = defaultLongitude;
        this.defaultLocation = defaultLocation;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
    }

    public String getCurrentWeather() {
        return getCurrentWeather(defaultLocation, defaultLatitude, defaultLongitude);
    }

    public String getCurrentWeather(String cityName) {
        double[] coords = CITY_COORDS.get(cityName);
        if (coords != null) {
            return getCurrentWeather(cityName, coords[0], coords[1]);
        }
        return getCurrentWeather();
    }

    public String detectAndGetWeather(String userMessage) {
        if (userMessage == null) return null;
        for (String city : CITY_COORDS.keySet()) {
            if (userMessage.contains(city)) {
                return getCurrentWeather(city);
            }
        }
        return getCurrentWeather();
    }

    private String getCurrentWeather(String location, double lat, double lon) {
        String url = "https://api.open-meteo.com/v1/forecast"
                + "?latitude=" + lat
                + "&longitude=" + lon
                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
                + "&timezone=Asia%2FShanghai";

        Request request = new Request.Builder().url(url).get().build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                log.warn("天气 API 请求失败: {}", response.code());
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            return parseWeather(body, location);
        } catch (IOException e) {
            log.warn("天气 API 调用异常: {}", e.getMessage());
            return null;
        }
    }

    private String parseWeather(String json, String location) {
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode current = root.get("current");
            if (current == null) return null;

            double temp = current.get("temperature_2m").asDouble();
            int humidity = current.get("relative_humidity_2m").asInt();
            int weatherCode = current.get("weather_code").asInt();
            double windSpeed = current.get("wind_speed_10m").asDouble();

            String weatherDesc = weatherCode < WEATHER_CODES.length
                    ? WEATHER_CODES[weatherCode] : "未知";

            return String.format("当前%s天气：%s，温度%.1f°C，湿度%d%%，风速%.1fkm/h",
                    location, weatherDesc, temp, humidity, windSpeed);
        } catch (Exception e) {
            log.warn("解析天气数据失败: {}", e.getMessage());
            return null;
        }
    }
}
