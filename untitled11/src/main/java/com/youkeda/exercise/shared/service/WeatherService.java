package com.youkeda.exercise.shared.service;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.exercise.shared.config.AmapConfig;

@Service
public class WeatherService {

    private static final String AMAP_WEATHER_URL = "https://restapi.amap.com/v3/weather/weatherInfo";

    private final AmapConfig amapConfig;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherService(AmapConfig amapConfig, RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.amapConfig = amapConfig;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String getWeather(String city) throws Exception {
        String url = AMAP_WEATHER_URL + "?key=" + amapConfig.getKey()
                + "&city=" + city
                + "&extensions=base"
                + "&output=JSON";

        String responseBody = restTemplate.getForObject(url, String.class);
        if (responseBody == null || responseBody.isBlank()) {
            return "[错误] 天气服务返回空响应";
        }

        JsonNode root = objectMapper.readTree(responseBody);
        String status = root.path("status").asText();
        String info = root.path("info").asText();

        if (!"1".equals(status)) {
            return "[错误] 天气服务返回错误: " + info;
        }

        JsonNode lives = root.path("lives");
        if (lives.isEmpty()) {
            return "[错误] 未找到城市 '" + city + "' 的天气数据";
        }

        JsonNode live = lives.get(0);
        String province = live.path("province").asText("N/A");
        String cityName = live.path("city").asText("N/A");
        String weather = live.path("weather").asText("N/A");
        String temperature = live.path("temperature").asText("N/A");
        String winddirection = live.path("winddirection").asText("N/A");
        String windpower = live.path("windpower").asText("N/A");
        String humidity = live.path("humidity").asText("N/A");
        String reporttime = live.path("reporttime").asText("N/A");

        return "===== " + province + " " + cityName + " 天气 =====\n"
                + "天气: " + weather + "\n"
                + "温度: " + temperature + "°C\n"
                + "湿度: " + humidity + "%\n"
                + "风向: " + winddirection + "\n"
                + "风力: " + windpower + " 级\n"
                + "数据时间: " + reporttime;
    }
}
