package com.youkeda.exercise.shared.controller;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.youkeda.exercise.shared.service.WeatherService;

@RestController
public class WeatherController {

    private static final String VERSION = "1.0.0";
    private static final String APP_NAME = "WeatherApp";

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping(value = "/help", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String help() {
        return "可用命令:\n"
                + "  /help               - 显示帮助信息\n"
                + "  /version            - 显示版本\n"
                + "  /status             - 显示程序状态\n"
                + "  /weather?city=<城市名> - 查询城市天气\n";
    }

    @GetMapping(value = "/version", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String version() {
        return APP_NAME + " 版本 " + VERSION;
    }

    @GetMapping(value = "/status", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String status() {
        return "程序状态: 运行中\n"
                + "版本: " + VERSION + "\n"
                + "当前时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @GetMapping(value = "/weather", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=UTF-8")
    public String weather(@RequestParam(required = false) String city) {
        if (city == null || city.isBlank()) {
            return "[错误] 请提供城市名称，例如: /weather?city=北京";
        }
        try {
            return weatherService.getWeather(city);
        } catch (Exception e) {
            return "[错误] 查询天气失败: " + e.getMessage();
        }
    }
}
