package com.example.ilink.feature.travel;

import com.example.ilink.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** 高德地点、驾车路线和静态地图接口的轻量封装。 */
public final class AmapService {

    private final HttpClient client;

    public AmapService(HttpClient client) {
        this.client = client;
    }

    public boolean isConfigured() { return !Config.AMAP_API_KEY.isBlank(); }

    public Place geocode(String name) throws Exception {
        JsonObject json = getJson("https://restapi.amap.com/v3/geocode/geo", "address=" + encode(name));
        JsonArray geocodes = json.getAsJsonArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) return null;
        JsonObject result = geocodes.get(0).getAsJsonObject();
        String[] point = result.get("location").getAsString().split(",");
        return new Place(result.get("formatted_address").getAsString(), point[0], point[1]);
    }

    public Route driving(Place from, Place to) throws Exception {
        String query = "origin=" + from.location() + "&destination=" + to.location() + "&extensions=base";
        JsonObject json = getJson("https://restapi.amap.com/v3/direction/driving", query);
        JsonArray paths = json.getAsJsonObject("route").getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) return null;
        JsonObject path = paths.get(0).getAsJsonObject();
        JsonArray steps = path.getAsJsonArray("steps");
        String midpoint = from.location();
        if (steps != null && !steps.isEmpty()) {
            JsonObject step = steps.get(steps.size() / 2).getAsJsonObject();
            String polyline = step.get("polyline").getAsString();
            if (!polyline.isBlank()) midpoint = polyline.split(";")[0];
        }
        return new Route(Integer.parseInt(path.get("distance").getAsString()),
                Integer.parseInt(path.get("duration").getAsString()), midpoint);
    }

    /** 根据用户当前位置检索两公里内的餐饮 POI，结果来自高德而非语言模型猜测。 */
    public List<Restaurant> nearbyRestaurants(String locationName) throws Exception {
        Place center = geocode(locationName);
        if (center == null) return List.of();
        return nearbyRestaurants(center);
    }

    /** 以已确认的地点坐标搜索附近餐饮，避免同名地点被重新解析为别处。 */
    public List<Restaurant> nearbyRestaurants(Place center) throws Exception {
        return nearbyRestaurants(center, "美食");
    }

    /** 在指定坐标附近按餐品关键词搜索，例如“面馆”或“杭帮菜”。 */
    public List<Restaurant> nearbyRestaurants(Place center, String keyword) throws Exception {
        String query = "location=" + center.location() + "&keywords=" + encode(keyword)
                + "&types=050000&radius=2000&offset=5&page=1&extensions=base";
        JsonObject json = getJson("https://restapi.amap.com/v3/place/around", query);
        JsonArray pois = json.getAsJsonArray("pois");
        if (pois == null) return List.of();
        List<Restaurant> restaurants = new ArrayList<>();
        for (int index = 0; index < pois.size(); index++) {
            JsonObject poi = pois.get(index).getAsJsonObject();
            String[] point = poi.get("location").getAsString().split(",");
            restaurants.add(new Restaurant(poi.get("name").getAsString(),
                    poi.has("address") ? poi.get("address").getAsString() : "", point[0], point[1]));
        }
        return restaurants;
    }

    /** 搜索地点候选项；多条结果时由上层向用户确认，不能默认选择第一条。 */
    public List<Place> searchPlaceCandidates(String locationName) throws Exception {
        JsonObject json = getJson("https://restapi.amap.com/v3/place/text",
                "keywords=" + encode(locationName) + "&offset=5&page=1&extensions=base");
        JsonArray pois = json.getAsJsonArray("pois");
        if (pois == null) return List.of();
        List<Place> candidates = new ArrayList<>();
        for (int index = 0; index < pois.size(); index++) {
            JsonObject poi = pois.get(index).getAsJsonObject();
            if (!poi.has("location") || poi.get("location").getAsString().isBlank()) continue;
            String[] point = poi.get("location").getAsString().split(",");
            String address = poi.has("address") ? poi.get("address").getAsString() : "";
            String label = poi.get("name").getAsString() + (address.isBlank() ? "" : "（" + address + "）");
            candidates.add(new Place(label, point[0], point[1]));
        }
        return candidates;
    }

    /** 下载带起终点标记的静态地图图片，直接作为微信图片发送。 */
    public byte[] staticMap(Place from, Place to) throws Exception {
        String markers = "mid,,A:" + from.location() + "|mid,,B:" + to.location();
        String url = "https://restapi.amap.com/v3/staticmap?key=" + encode(Config.AMAP_API_KEY)
                + "&size=750*400&markers=" + encode(markers);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) return null;
        return response.body();
    }

    /** 将当前位置与候选店铺同时标记在一张静态地图上，方便用户先看分布再选店。 */
    public byte[] nearbyStaticMap(Place center, List<Restaurant> restaurants) throws Exception {
        StringBuilder markers = new StringBuilder("mid,,C:").append(center.location());
        for (int index = 0; index < restaurants.size(); index++) {
            markers.append("|mid,,").append(index + 1).append(':').append(restaurants.get(index).location());
        }
        return staticMap(markers.toString());
    }

    /** 将同名地点候选项标记在一张图上，供用户按序号确认。 */
    public byte[] candidateStaticMap(List<Place> candidates) throws Exception {
        StringBuilder markers = new StringBuilder();
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) markers.append('|');
            markers.append("mid,,").append(index + 1).append(':').append(candidates.get(index).location());
        }
        return staticMap(markers.toString());
    }

    /** 动态导航由高德官方页面承接，用户可以继续缩放、换路线或唤起 App。 */
    public String navigationUrl(Place to) {
        return "https://uri.amap.com/navigation?to=" + to.location() + "," + encode(to.name())
                + "&mode=car&coordinate=gaode&callnative=1";
    }

    /** 手机端打开指定店铺位置，可继续查看详情或从当前位置导航。 */
    public String restaurantUrl(Restaurant restaurant) {
        return "https://uri.amap.com/marker?position=" + restaurant.location() + "&name="
                + encode(restaurant.name()) + "&callnative=1";
    }

    private byte[] staticMap(String markers) throws Exception {
        String url = "https://restapi.amap.com/v3/staticmap?key=" + encode(Config.AMAP_API_KEY)
                + "&size=750*400&markers=" + encode(markers);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200 || response.body().length == 0) return null;
        return response.body();
    }

    private JsonObject getJson(String endpoint, String query) throws Exception {
        String url = endpoint + "?key=" + encode(Config.AMAP_API_KEY) + "&" + query;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("高德服务暂时不可用");
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!"1".equals(json.get("status").getAsString())) {
            throw new IllegalStateException(json.has("info") ? json.get("info").getAsString() : "地点查询失败");
        }
        return json;
    }

    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    public record Place(String name, String longitude, String latitude) {
        public String location() { return longitude + "," + latitude; }
    }

    public record Route(int distanceMeters, int durationSeconds, String midpoint) { }

    public record Restaurant(String name, String address, String longitude, String latitude) {
        public String location() { return longitude + "," + latitude; }
    }
}
