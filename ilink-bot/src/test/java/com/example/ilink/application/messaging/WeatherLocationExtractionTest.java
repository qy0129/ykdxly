package com.example.ilink.application.messaging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeatherLocationExtractionTest {

    @Test
    void extractsCityFromCurrentLocationSentence() {
        assertEquals("南京", UserRequestHandler.extractWeatherLocation("我在南京，今天天气怎么样"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation("我现在在杭州，明天天气"));
        assertEquals("北京", UserRequestHandler.extractWeatherLocation("帮我查北京天气"));
        assertEquals("杭州", UserRequestHandler.extractWeatherLocation(
                "今天杭州的天气怎么样？我现在想打车去杭州西湖"));
    }
}
