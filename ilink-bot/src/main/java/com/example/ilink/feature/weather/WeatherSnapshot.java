package com.example.ilink.feature.weather;

/** 一次天气请求同时返回文字摘要和背景所需的标准化状态。 */
public record WeatherSnapshot(String text, WeatherVisualState visual) {
}
