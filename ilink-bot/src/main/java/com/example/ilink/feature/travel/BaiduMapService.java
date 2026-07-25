package com.example.ilink.feature.travel;

import com.example.ilink.config.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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

/** 百度地图地点检索、驾车路线、静态地图和导航链接实现。 */
public final class BaiduMapService extends AmapService {

    private final HttpClient client;
    private volatile boolean useAmapFallback;
    private volatile boolean fallbackLogged;

    public BaiduMapService(HttpClient client) {
        super(client);
        this.client = client;
    }

    @Override
    public boolean isConfigured() {
        return !Config.BAIDU_MAP_AK.isBlank();
    }

    @Override
    public Place geocode(String name) throws Exception {
        return geocode(name, "");
    }

    @Override
    public Place geocode(String name, String city) throws Exception {
        if (useAmapFallback) return super.geocode(name, city);
        List<Place> candidates = searchPlaceCandidates(name, city);
        if (!candidates.isEmpty()) return candidates.getFirst();
        String query = "address=" + encode(name) + "&output=json";
        if (city != null && !city.isBlank()) query += "&city=" + encode(city);
        JsonObject json;
        try {
            json = getJson("https://api.map.baidu.com/geocoding/v3/", query);
        } catch (Exception error) {
            if (useAmapFallback) return super.geocode(name, city);
            throw error;
        }
        JsonObject result = json.getAsJsonObject("result");
        if (result == null || !result.has("location")) return null;
        JsonObject location = result.getAsJsonObject("location");
        return new Place(name, value(location, "lng"), value(location, "lat"));
    }

    @Override
    public List<Place> searchPlaceCandidates(String locationName) throws Exception {
        return searchPlaceCandidates(locationName, "");
    }

    @Override
    public List<Place> searchPlaceCandidates(String locationName, String city) throws Exception {
        if (useAmapFallback) return super.searchPlaceCandidates(locationName, city);
        String region = city == null || city.isBlank() ? "全国" : city;
        JsonObject json;
        try {
            json = getJson("https://api.map.baidu.com/place/v2/search",
                    "query=" + encode(locationName) + "&region=" + encode(region)
                            + "&city_limit=" + (!"全国".equals(region)) + "&output=json&page_size=5");
        } catch (Exception error) {
            if (useAmapFallback) return super.searchPlaceCandidates(locationName, city);
            throw error;
        }
        JsonArray results = json.getAsJsonArray("results");
        if (results == null) return List.of();
        List<Place> places = new ArrayList<>();
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            if (!result.has("location")) continue;
            JsonObject location = result.getAsJsonObject("location");
            String address = result.has("address") ? result.get("address").getAsString() : "";
            String label = result.get("name").getAsString() + (address.isBlank() ? "" : "（" + address + "）");
            places.add(new Place(label, value(location, "lng"), value(location, "lat")));
        }
        return places;
    }

    @Override
    public Route driving(Place from, Place to) throws Exception {
        return driving(from, null, to);
    }

    @Override
    public Route drivingVia(Place from, Place via, Place to) throws Exception {
        return driving(from, via, to);
    }

    private Route driving(Place from, Place via, Place to) throws Exception {
        if (useAmapFallback) return via == null ? super.driving(from, to) : super.drivingVia(from, via, to);
        String query = "origin=" + latLng(from) + "&destination=" + latLng(to);
        if (via != null) query += "&waypoints=" + latLng(via);
        JsonObject json;
        try {
            json = getJson("https://api.map.baidu.com/directionlite/v1/driving", query);
        } catch (Exception error) {
            if (useAmapFallback) return via == null ? super.driving(from, to) : super.drivingVia(from, via, to);
            throw error;
        }
        JsonObject result = json.getAsJsonObject("result");
        JsonArray routes = result == null ? null : result.getAsJsonArray("routes");
        if (routes == null || routes.isEmpty()) return null;
        JsonObject route = routes.get(0).getAsJsonObject();
        return new Route(route.get("distance").getAsInt(), route.get("duration").getAsInt(),
                sampleRouteLocations(route.getAsJsonArray("steps"), from.location()));
    }

    @Override
    public List<Restaurant> nearbyRestaurants(String locationName) throws Exception {
        Place center = geocode(locationName);
        return center == null ? List.of() : nearbyRestaurants(center);
    }

    @Override
    public List<Restaurant> nearbyRestaurants(Place center) throws Exception {
        return nearbyRestaurants(center, "美食");
    }

    @Override
    public List<Restaurant> nearbyRestaurants(Place center, String keyword) throws Exception {
        if (useAmapFallback) return super.nearbyRestaurants(center, keyword);
        JsonObject json;
        try {
            json = getJson("https://api.map.baidu.com/place/v2/search",
                    "query=" + encode(keyword) + "&location=" + center.latitude() + "," + center.longitude()
                            + "&radius=2000&scope=1&output=json&page_size=5");
        } catch (Exception error) {
            if (useAmapFallback) return super.nearbyRestaurants(center, keyword);
            throw error;
        }
        JsonArray results = json.getAsJsonArray("results");
        if (results == null) return List.of();
        List<Restaurant> restaurants = new ArrayList<>();
        for (JsonElement element : results) {
            JsonObject result = element.getAsJsonObject();
            if (!result.has("location")) continue;
            JsonObject location = result.getAsJsonObject("location");
            restaurants.add(new Restaurant(result.get("name").getAsString(),
                    result.has("address") ? result.get("address").getAsString() : "",
                    value(location, "lng"), value(location, "lat")));
        }
        return restaurants;
    }

    @Override
    public byte[] staticMap(Place from, Place to) throws Exception {
        return staticMap(List.of(from, to));
    }

    @Override
    public byte[] staticMap(List<Place> itinerary) throws Exception {
        return staticMapForPlaces(itinerary);
    }

    @Override
    public byte[] nearbyStaticMap(Place center, List<Restaurant> restaurants) throws Exception {
        List<Place> places = new ArrayList<>();
        places.add(center);
        restaurants.forEach(restaurant -> places.add(new Place(
                restaurant.name(), restaurant.longitude(), restaurant.latitude())));
        return staticMapForPlaces(places);
    }

    @Override
    public byte[] candidateStaticMap(List<Place> candidates) throws Exception {
        return staticMapForPlaces(candidates);
    }

    @Override
    public byte[] candidateStaticMap(Place candidate, int number) throws Exception {
        return staticMapForPlaces(List.of(candidate));
    }

    private byte[] staticMapForPlaces(List<Place> places) throws Exception {
        if (places == null || places.isEmpty()) return null;
        if (useAmapFallback) return super.staticMap(places);
        String markers = String.join("|", places.stream().map(Place::location).toList());
        String url = "https://api.map.baidu.com/staticimage/v2?ak=" + encode(Config.BAIDU_MAP_AK)
                + "&width=750&height=400&markers=" + encode(markers);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (response.statusCode() == 200 && response.body().length > 0 && contentType.startsWith("image/")) {
            return response.body();
        }
        enableAmapFallback("百度静态地图权限不可用");
        return super.staticMap(places);
    }

    @Override
    public String navigationUrl(Place from, Place to) {
        return navigationUrl(List.of(from, to));
    }

    @Override
    public String navigationUrl(List<Place> itinerary) {
        if (itinerary == null || itinerary.size() < 2) return "";
        StringBuilder url = new StringBuilder("https://api.map.baidu.com/direction?origin=")
                .append(encode(uriPlace(itinerary.getFirst())))
                .append("&destination=").append(encode(uriPlace(itinerary.getLast())))
                .append("&mode=driving&region=").append(encode("全国"))
                .append("&output=html&src=ilink-bot");
        if (itinerary.size() > 2) {
            String waypoints = String.join("|", itinerary.subList(1, itinerary.size() - 1).stream()
                    .map(this::uriPlace).toList());
            url.append("&waypoints=").append(encode(waypoints));
        }
        return url.toString();
    }

    @Override
    public String restaurantDetourUrl(List<Place> itinerary, Restaurant restaurant, int legIndex) {
        if (itinerary == null || itinerary.size() < 2 || legIndex < 0 || legIndex >= itinerary.size() - 1) return "";
        List<Place> route = new ArrayList<>(itinerary);
        route.add(legIndex + 1, new Place(restaurant.name(), restaurant.longitude(), restaurant.latitude()));
        return navigationUrl(route);
    }

    @Override
    public String restaurantUrl(Restaurant restaurant) {
        return "https://api.map.baidu.com/marker?location=" + restaurant.latitude() + "," + restaurant.longitude()
                + "&title=" + encode(restaurant.name()) + "&content=" + encode(restaurant.address())
                + "&output=html&src=ilink-bot";
    }

    private JsonObject getJson(String endpoint, String query) throws Exception {
        String url = endpoint + "?ak=" + encode(Config.BAIDU_MAP_AK) + "&" + query;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("百度地图服务暂时不可用");
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        if (!json.has("status") || json.get("status").getAsInt() != 0) {
            String message = json.has("message") ? json.get("message").getAsString() : "百度地图查询失败";
            int status = json.has("status") ? json.get("status").getAsInt() : -1;
            if (status == 101 || status == 200 || status == 240) enableAmapFallback(message);
            throw new IllegalStateException(message);
        }
        return json;
    }

    private List<String> sampleRouteLocations(JsonArray steps, String fallback) {
        List<String> points = new ArrayList<>();
        if (steps != null) {
            for (JsonElement element : steps) {
                JsonObject step = element.getAsJsonObject();
                if (!step.has("path")) continue;
                for (String point : step.get("path").getAsString().split(";")) {
                    if (!point.isBlank()) points.add(point);
                }
            }
        }
        if (points.isEmpty()) return List.of(fallback);
        Set<String> samples = new LinkedHashSet<>();
        int last = points.size() - 1;
        for (int ratio : List.of(1, 2, 3)) samples.add(points.get(last * ratio / 4));
        return List.copyOf(samples);
    }

    private String latLng(Place place) {
        return place.latitude() + "," + place.longitude();
    }

    private String uriPlace(Place place) {
        return useAmapFallback ? "name:" + place.name()
                : "latlng:" + place.latitude() + "," + place.longitude() + "|name:" + place.name();
    }

    private void enableAmapFallback(String reason) {
        useAmapFallback = true;
        if (!fallbackLogged) {
            fallbackLogged = true;
            System.err.println("[百度地图] Web服务不可用，暂用高德计算路线；百度导航入口保留。原因：" + reason);
        }
    }

    private String value(JsonObject object, String name) {
        return object.get(name).getAsString();
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
