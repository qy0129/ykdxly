package com.example.ilink.capabilities.travel;

import com.example.ilink.bootstrap.Config;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 高德地点、驾车路线和静态地图接口的轻量封装。 */
public class AmapService {

    private static final long DRIVING_REQUEST_INTERVAL_MILLIS = 600;
    private static final long DRIVING_RETRY_DELAY_MILLIS = 1200;

    private final HttpClient client;
    private final Object drivingRequestLock = new Object();
    private long nextDrivingRequestTime;

    public AmapService(HttpClient client) {
        this.client = client;
    }

    public boolean isConfigured() { return !Config.AMAP_API_KEY.isBlank(); }

    /**
     * 解析用户描述的地点。
     * 园区、车站和商场优先按 POI 搜索，避免普通地址地理编码把同名区域解析到错误行政区。
     */
    public Place geocode(String name) throws Exception {
        return geocode(name, "");
    }

    /** 在已知城市范围内解析地点，减少全国同名园区、车站和商场的干扰。 */
    public Place geocode(String name, String city) throws Exception {
        List<Place> poiCandidates = searchPlaceCandidates(name, city);
        if (!poiCandidates.isEmpty()) {
            return poiCandidates.get(0);
        }
        return geocodeAddress(name, city);
    }

    /** 将手机 GPS 或百度坐标转换为高德坐标后执行逆地理编码。 */
    public Place reverseGeocode(double longitude, double latitude, String coordinateSystem) throws Exception {
        validateCoordinate(longitude, latitude);
        String source = coordinateSystem == null ? "gps" : coordinateSystem.trim().toLowerCase();
        String location = longitude + "," + latitude;
        if (!"amap".equals(source) && !"gaode".equals(source)) {
            String coordsys = "baidu".equals(source) ? "baidu" : "gps";
            JsonObject converted = getJson("https://restapi.amap.com/v3/assistant/coordinate/convert",
                    "locations=" + encode(location) + "&coordsys=" + coordsys);
            location = converted.has("locations") ? converted.get("locations").getAsString() : "";
            if (location.isBlank()) throw new IllegalStateException("坐标转换失败");
        }

        JsonObject json = getJson("https://restapi.amap.com/v3/geocode/regeo",
                "location=" + encode(location) + "&extensions=base&radius=1000");
        JsonObject regeocode = json.getAsJsonObject("regeocode");
        if (regeocode == null || !regeocode.has("formatted_address")) return null;
        String address = regeocode.get("formatted_address").getAsString();
        if (address.isBlank()) return null;
        String[] point = location.split(",");
        return new Place(address, point[0], point[1]);
    }

    /** 使用地址地理编码作为 POI 搜索没有结果时的兜底。 */
    private Place geocodeAddress(String name) throws Exception {
        return geocodeAddress(name, "");
    }

    private Place geocodeAddress(String name, String city) throws Exception {
        String query = "address=" + encode(name);
        if (city != null && !city.isBlank()) query += "&city=" + encode(city);
        JsonObject json = getJson("https://restapi.amap.com/v3/geocode/geo", query);
        JsonArray geocodes = json.getAsJsonArray("geocodes");
        if (geocodes == null || geocodes.isEmpty()) return null;
        JsonObject result = geocodes.get(0).getAsJsonObject();
        String[] point = result.get("location").getAsString().split(",");
        return new Place(result.get("formatted_address").getAsString(), point[0], point[1]);
    }

    public Route driving(Place from, Place to) throws Exception {
        return driving(from, null, to);
    }

    /**
     * 规划经过指定地点的完整驾车路线。
     * 一次请求即可获得“起点-途经点-终点”的总耗时，用于计算餐厅真实绕路时间。
     */
    public Route drivingVia(Place from, Place via, Place to) throws Exception {
        return driving(from, via, to);
    }

    /** 构造普通路线或带单个途经点的路线，并解析统一的路线结果。 */
    private Route driving(Place from, Place via, Place to) throws Exception {
        String query = "origin=" + from.location() + "&destination=" + to.location() + "&extensions=base";
        if (via != null) query += "&waypoints=" + via.location();
        JsonObject json = getDrivingJson(query);
        JsonArray paths = json.getAsJsonObject("route").getAsJsonArray("paths");
        if (paths == null || paths.isEmpty()) return null;
        JsonObject path = paths.get(0).getAsJsonObject();
        JsonArray steps = path.getAsJsonArray("steps");
        List<String> sampledLocations = sampleRouteLocations(steps, from.location());
        return new Route(Integer.parseInt(path.get("distance").getAsString()),
                Integer.parseInt(path.get("duration").getAsString()), sampledLocations);
    }

    /** 驾车接口统一限速；遇到高德每秒配额限制时等待后只重试一次。 */
    private JsonObject getDrivingJson(String query) throws Exception {
        try {
            return getDrivingJsonOnce(query);
        } catch (IllegalStateException error) {
            if (!error.getMessage().contains("CUQPS_HAS_EXCEEDED_THE_LIMIT")) throw error;
            Thread.sleep(DRIVING_RETRY_DELAY_MILLIS);
            return getDrivingJsonOnce(query);
        }
    }

    /** 等待可用请求时隙后调用高德驾车路线接口。 */
    private JsonObject getDrivingJsonOnce(String query) throws Exception {
        waitForDrivingRequestSlot();
        return getJson("https://restapi.amap.com/v3/direction/driving", query);
    }

    /** 保证同一个机器人实例不会在一秒内密集发送多次驾车请求。 */
    private void waitForDrivingRequestSlot() throws InterruptedException {
        synchronized (drivingRequestLock) {
            long waitMillis = nextDrivingRequestTime - System.currentTimeMillis();
            if (waitMillis > 0) Thread.sleep(waitMillis);
            nextDrivingRequestTime = System.currentTimeMillis() + DRIVING_REQUEST_INTERVAL_MILLIS;
        }
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
        return nearbyRestaurants(center, keyword, 5);
    }

    /** 在指定坐标附近按餐品关键词搜索，并按调用方要求控制返回数量。 */
    public List<Restaurant> nearbyRestaurants(Place center, String keyword, int limit) throws Exception {
        int resultLimit = Math.max(1, Math.min(20, limit));
        String query = "location=" + center.location() + "&keywords=" + encode(keyword)
                + "&types=050000&radius=2000&offset=" + resultLimit + "&page=1&extensions=base";
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
        return searchPlaceCandidates(locationName, "");
    }

    /** 使用城市作为搜索范围；城市为空时保持原有全国搜索行为。 */
    public List<Place> searchPlaceCandidates(String locationName, String city) throws Exception {
        String query = "keywords=" + encode(locationName) + "&offset=5&page=1&extensions=base";
        if (city != null && !city.isBlank()) query += "&city=" + encode(city);
        JsonObject json = getJson("https://restapi.amap.com/v3/place/text", query);
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
        return staticMap(List.of(from, to));
    }

    /** 下载标记起点、全部途经点和终点的行程总览地图。 */
    public byte[] staticMap(List<Place> itinerary) throws Exception {
        StringBuilder markers = new StringBuilder();
        for (int index = 0; index < itinerary.size(); index++) {
            if (index > 0) markers.append('|');
            markers.append("mid,,").append(index + 1).append(':').append(itinerary.get(index).location());
        }
        return staticMap(markers.toString());
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

    /** 为单个地点候选生成独立地图，并保留它在候选列表中的序号。 */
    public byte[] candidateStaticMap(Place candidate, int number) throws Exception {
        return staticMap("mid,," + Math.max(1, number) + ':' + candidate.location());
    }

    /** 动态导航由高德官方页面承接，用户可以继续缩放、换路线或唤起 App。 */
    public String navigationUrl(Place from, Place to) {
        return navigationUrl(List.of(from, to));
    }

    /** 生成包含全部途经点的单一高德导航链接。 */
    public String navigationUrl(List<Place> itinerary) {
        if (itinerary == null || itinerary.size() < 2) return "";
        Place from = itinerary.getFirst();
        Place to = itinerary.getLast();
        StringBuilder url = new StringBuilder("https://uri.amap.com/navigation?from=")
                .append(from.location()).append(',').append(encode(from.name()))
                .append("&to=").append(to.location()).append(',').append(encode(to.name()));
        if (itinerary.size() > 2) {
            url.append("&via=");
            for (int index = 1; index < itinerary.size() - 1; index++) {
                if (index > 1) url.append("%7C");
                Place stop = itinerary.get(index);
                url.append(stop.location()).append(',').append(encode(stop.name()));
            }
        }
        return url.append("&mode=car&coordinate=gaode&callnative=1").toString();
    }

    /** 将推荐餐厅插入其所属路段，生成保持原有途经顺序的完整导航链接。 */
    public String restaurantDetourUrl(List<Place> itinerary, Restaurant restaurant, int legIndex) {
        if (itinerary == null || itinerary.size() < 2
                || legIndex < 0 || legIndex >= itinerary.size() - 1) return "";
        List<Place> detourItinerary = new ArrayList<>(itinerary);
        detourItinerary.add(legIndex + 1, new Place(
                restaurant.name(), restaurant.longitude(), restaurant.latitude()));
        return navigationUrl(detourItinerary);
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

    private void validateCoordinate(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude)
                || longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("经纬度不合法");
        }
    }

    /**
     * 从导航步骤的折线坐标中抽取 25%、50% 和 75% 三个沿途采样点。
     * 这些点用于筛选顺路餐厅，不将单一路线中点误当作整条路线的代表。
     */
    private List<String> sampleRouteLocations(JsonArray steps, String fallbackLocation) {
        List<String> locations = new ArrayList<>();
        if (steps != null) {
            for (int index = 0; index < steps.size(); index++) {
                JsonObject step = steps.get(index).getAsJsonObject();
                if (!step.has("polyline")) continue;
                String polyline = step.get("polyline").getAsString();
                if (polyline.isBlank()) continue;
                for (String location : polyline.split(";")) {
                    if (!location.isBlank()) locations.add(location);
                }
            }
        }
        if (locations.isEmpty()) return List.of(fallbackLocation);

        Set<String> samples = new LinkedHashSet<>();
        int lastIndex = locations.size() - 1;
        for (int ratio : List.of(1, 2, 3)) {
            samples.add(locations.get(lastIndex * ratio / 4));
        }
        return List.copyOf(samples);
    }

    public record Place(String name, String longitude, String latitude) {
        public String location() { return longitude + "," + latitude; }
    }

    /** 驾车路线的距离、耗时和用于沿途服务的采样坐标。 */
    public record Route(int distanceMeters, int durationSeconds, List<String> sampledLocations) { }

    public record Restaurant(String name, String address, String longitude, String latitude) {
        public String location() { return longitude + "," + latitude; }
    }
}
