package com.youkeda.exercise.shared;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

import org.json.JSONArray;
import org.json.JSONObject;

public class Main {

    private static final String VERSION = "1.0.0";
    private static final String APP_NAME = "MyCLI";
    private static boolean running = true;

    public static void main(String[] args) {
        Scanner scanner = new Scanner(System.in);
        System.out.println(APP_NAME + " v" + VERSION + " - 输入 'help' 查看帮助");

        while (running) {
            try {
                System.out.print("> ");
                String input = scanner.nextLine().trim();
                if (input.isEmpty()) continue;
                String[] parts = input.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String arg = parts.length > 1 ? parts[1] : null;

                switch (cmd) {
                    case "help" -> handleHelp();
                    case "version" -> handleVersion();
                    case "status" -> handleStatus();
                    case "weather" -> handleWeather(arg);
                    case "exit", "quit" -> {
                        System.out.println("再见！");
                        running = false;
                    }
                    default -> System.out.println("未知命令: " + cmd + "。输入 'help' 查看可用命令。");
                }
            } catch (Exception e) {
                System.out.println("[错误] " + e.getMessage());
            }
        }
        scanner.close();
    }

    private static void handleHelp() {
        System.out.println("可用命令:");
        System.out.println("  help               - 显示帮助信息");
        System.out.println("  version            - 显示版本");
        System.out.println("  status             - 显示程序状态");
        System.out.println("  weather <城市名>    - 查询城市天气");
        System.out.println("  exit / quit        - 退出程序");
    }

    private static void handleVersion() {
        System.out.println(APP_NAME + " 版本 " + VERSION);
    }

    private static void handleStatus() {
        System.out.println("程序状态: 运行中");
        System.out.println("版本: " + VERSION);
        System.out.println("当前时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }

    private static void handleWeather(String city) {
        if (city == null || city.isBlank()) {
            System.out.println("[错误]请提供城市名称，例如: weather 北京");
            return;
        }
        try {
            HttpClient client = HttpClient.newHttpClient();
            String encoded = URLEncoder.encode(city, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://wttr.in/" + encoded + "?format=j1"))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.out.println("[错误] 天气服务返回状态码: " + response.statusCode());
                return;
            }

            JSONObject json = new JSONObject(response.body());
            JSONArray current = json.getJSONArray("current_condition");
            if (current.isEmpty()) {
                System.out.println("[错误] 未找到城市 '" + city + "' 的天气数据");
                return;
            }

            JSONObject cond = current.getJSONObject(0);
            String temp = cond.optString("temp_C", "N/A");
            String feelsLike = cond.optString("FeelsLikeC", "N/A");
            String humidity = cond.optString("humidity", "N/A");
            String desc = cond.optJSONArray("weatherDesc").optJSONObject(0).optString("value", "N/A");
            String windSpeed = cond.optString("windspeedKmph", "N/A");
            String windDir = cond.optString("winddir16Point", "N/A");

            System.out.println("===== " + city + " 天气 =====");
            System.out.println("天气: " + desc);
            System.out.println("温度: " + temp + "°C (体感 " + feelsLike + "°C)");
            System.out.println("湿度: " + humidity + "%");
            System.out.println("风向: " + windDir + " 风速: " + windSpeed + " km/h");

        } catch (java.net.http.HttpTimeoutException e) {
            System.out.println("[错误] 请求天气服务超时，请稍后重试");
        } catch (java.net.ConnectException e) {
            System.out.println("[错误] 无法连接到天气服务，请检查网络连接");
        } catch (Exception e) {
            System.out.println("[错误] 查询天气失败: " + e.getMessage());
        }
    }
}