package com.example.ilink.feature.weather;

/** 提供给天气背景渲染器的标准化状态，不暴露 Open-Meteo 响应结构。 */
public record WeatherVisualState(
        int weatherCode,
        String conditionGroup,
        String conditionName,
        boolean day,
        double cloudCover,
        double precipitation,
        int precipitationProbability,
        double windSpeed,
        double windDirection,
        double temperature,
        double feelsLike,
        String timezone) {
}
