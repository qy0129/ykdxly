package com.example.ilink.feature.express;

import com.example.ilink.config.Config;
import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ExpressPageRenderer {

    private static final String TEMPLATE = loadTemplate();
    private static final Map<String, double[]> CITY_COORDS = createCityCoords();

    private final Gson gson = new Gson();

    public String render(String token, ExpressResult result) {
        if (TEMPLATE.isBlank()) {
            return errorPage("模板文件加载失败");
        }

        List<TrackingItem> items = result.items() == null ? List.of() : result.items();
        String mapData = buildMapData(items);
        String baiduAk = Config.BAIDU_MAP_AK == null ? "" : Config.BAIDU_MAP_AK.trim();

        String html = TEMPLATE;
        html = html.replace("{{company}}", esc(safe(result.courierName())));
        html = html.replace("{{expressNo}}", esc(safe(result.trackingNo())));
        html = html.replace("{{status}}", esc(stateText(result.state())));
        html = html.replace("{{token}}", esc(token));
        html = html.replace("{{baiduAk}}", esc(baiduAk));
        html = html.replace("{{timeline}}", buildTimeline(items));
        return html.replace("{{mapData}}", mapData);
    }

    private String buildTimeline(List<TrackingItem> items) {
        if (items.isEmpty()) {
            return "<div class=\"empty\">暂时没有物流轨迹</div>";
        }

        StringBuilder html = new StringBuilder();
        for (int index = 0; index < items.size(); index++) {
            TrackingItem item = items.get(index);
            String time = firstNonBlank(item.ftime(), item.time());
            html.append("<div class=\"tl-item").append(index == 0 ? " active" : "").append("\">")
                    .append("<div class=\"tl-dot\"></div><div class=\"tl-line\"></div>")
                    .append("<div class=\"tl-time\">").append(esc(time)).append("</div>")
                    .append("<div class=\"tl-desc\">").append(esc(safe(item.context()))).append("</div>")
                    .append("</div>");
        }
        return html.toString();
    }

    /**
     * 接口时间线保持最新在前；地图路线必须使用其反转副本，以保证起点、终点和车辆的顺序一致。
     */
    private String buildMapData(List<TrackingItem> items) {
        List<RoutePoint> route = new ArrayList<>();
        for (TrackingItem item : items) {
            String area = firstNonBlank(item.areaName(), findCityIn(safe(item.context())), safe(item.context()));
            double[] coordinates = coordinatesFor(area);
            if (coordinates == null || routeContains(route, area, coordinates)) {
                continue;
            }
            route.add(new RoutePoint(area, firstNonBlank(item.ftime(), item.time()), safe(item.context()), coordinates));
        }

        Collections.reverse(route);
        JsonArray points = new JsonArray();
        for (RoutePoint point : route) {
            JsonObject json = new JsonObject();
            if (point.coordinates() != null) {
                json.addProperty("lng", point.coordinates()[0]);
                json.addProperty("lat", point.coordinates()[1]);
            }
            json.addProperty("area", point.area());
            json.addProperty("time", point.time());
            json.addProperty("context", point.context());
            points.add(json);
        }

        JsonObject data = new JsonObject();
        data.add("points", points);
        data.addProperty("hasRoute", points.size() >= 2);
        return gson.toJson(data);
    }

    private boolean routeContains(List<RoutePoint> route, String area, double[] coordinates) {
        for (RoutePoint point : route) {
            if (point.area().equals(area)
                    || (point.coordinates() != null && coordinates != null
                    && point.coordinates()[0] == coordinates[0] && point.coordinates()[1] == coordinates[1])) {
                return true;
            }
        }
        return false;
    }

    private double[] coordinatesFor(String area) {
        String city = findCityIn(area);
        return city == null ? null : CITY_COORDS.get(city);
    }

    private String findCityIn(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace(" ", "").trim();
        for (String city : CITY_COORDS.keySet()) {
            if (normalized.contains(city)) {
                return city;
            }
        }
        return "";
    }

    private static Map<String, double[]> createCityCoords() {
        Map<String, double[]> cities = new LinkedHashMap<>();
        cities.put("杭州", new double[]{120.1551, 30.2741});
        cities.put("南昌", new double[]{115.8579, 28.6820});
        cities.put("长沙", new double[]{112.9388, 28.2282});
        cities.put("武汉", new double[]{114.3054, 30.5931});
        cities.put("广州", new double[]{113.2644, 23.1291});
        cities.put("上海", new double[]{121.4737, 31.2304});
        cities.put("北京", new double[]{116.4074, 39.9042});
        cities.put("深圳", new double[]{114.0579, 22.5431});
        cities.put("成都", new double[]{104.0665, 30.5728});
        cities.put("重庆", new double[]{106.5516, 29.5630});
        cities.put("南京", new double[]{118.7969, 32.0603});
        cities.put("郑州", new double[]{113.6254, 34.7466});
        cities.put("西安", new double[]{108.9402, 34.3416});
        cities.put("合肥", new double[]{117.2272, 31.8206});
        cities.put("福州", new double[]{119.2965, 26.0745});
        cities.put("昆明", new double[]{102.7146, 25.0490});
        return cities;
    }

    private static String loadTemplate() {
        try (InputStream input = ExpressPageRenderer.class.getClassLoader()
                .getResourceAsStream("templates/express/detail.html")) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[快递页面] 加载模板失败: " + e.getMessage());
            return "";
        }
    }

    public String errorPage(String message) {
        return "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>错误</title></head><body><h2>" + esc(message)
                + "</h2><p>请重新查询快递</p></body></html>";
    }

    public String notFoundPage() {
        return errorPage("页面不存在或已过期");
    }

    public static String stateText(String state) {
        if (state == null || state.isBlank()) {
            return "暂无状态";
        }
        return switch (state) {
            case "0" -> "在途";
            case "1" -> "已揽收";
            case "2" -> "疑难";
            case "3" -> "已签收";
            case "4", "6" -> "已退回";
            case "5" -> "已派件";
            default -> "物流更新中";
        };
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String esc(String value) {
        return safe(value).replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private record RoutePoint(String area, String time, String context, double[] coordinates) {
    }
}
