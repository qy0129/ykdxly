package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherLocationExtractionTest {

    @Test
    void extractsCityFromCurrentLocationSentence() {
        assertEquals("南京", UserRequestHandler.extractWeatherLocation("我在南京，今天天气怎么样"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation("我现在在杭州，明天天气"));
        assertEquals("北京", UserRequestHandler.extractWeatherLocation("帮我查北京天气"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation("查询明天杭州的天气"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation(
                "今天杭州的天气怎么样？我现在想打车去杭州西湖"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation("如果杭州"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation("如果杭州下雨"));
    }

    @Test
    void evaluatesConditionalWeatherBranches() {
        var rain = UserRequestHandler.weatherDecision("杭州明天小雨，18 到 23℃");
        assertEquals(true, rain.suitable());
        assertEquals(true, rain.rain());

        var storm = UserRequestHandler.weatherDecision("杭州明天暴雨并伴随雷暴");
        assertEquals(false, storm.suitable());
        assertEquals(true, storm.rain());
    }
}
