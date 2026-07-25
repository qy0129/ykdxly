package com.example.ilink.feature.travel;

import com.example.ilink.config.Config;
import com.google.gson.Gson;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 滴滴 MCP Sandbox 的最小 JSON-RPC 客户端。 */
public final class DidiMcpClient {
    private static final String PRODUCTION_ENDPOINT =
            "https://mcp.didichuxing.com/mcp-servers?key=";
    private static final String SANDBOX_ENDPOINT =
            "https://mcp.didichuxing.com/mcp-servers-sandbox?key=";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s\\\"]+");

    private final HttpClient httpClient;
    private final String mcpKey;
    private final boolean sandbox;
    private final Gson gson = new Gson();
    private final AtomicLong requestIds = new AtomicLong(1);

    public DidiMcpClient() { this(HttpClient.newHttpClient(), Config.DIDI_MCP_KEY, Config.DIDI_MCP_SANDBOX); }

    public DidiMcpClient(HttpClient httpClient, String mcpKey) {
        this(httpClient, mcpKey, true);
    }

    public DidiMcpClient(HttpClient httpClient, String mcpKey, boolean sandbox) {
        this.httpClient = httpClient;
        this.mcpKey = mcpKey == null ? "" : mcpKey.trim();
        this.sandbox = sandbox;
    }

    public boolean isConfigured() { return !mcpKey.isBlank(); }
    public boolean isSandbox() { return sandbox; }

    public List<Place> textSearch(String keywords, String city) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("keywords", keywords);
        arguments.addProperty("city", city);
        JsonArray places = JsonParser.parseString(contentText(call("maps_textsearch", arguments))).getAsJsonArray();
        List<Place> values = new ArrayList<>();
        for (JsonElement element : places) {
            JsonObject place = element.getAsJsonObject();
            JsonObject location = object(place, "location");
            if (location == null) continue;
            values.add(new Place(value(place, "display_name"), value(place, "address"),
                    value(place, "address_all"), value(place, "city"),
                    value(location, "lng"), value(location, "lat")));
        }
        return List.copyOf(values);
    }

    public Estimate estimate(Place from, Place to) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("from_lng", from.longitude());
        arguments.addProperty("from_lat", from.latitude());
        arguments.addProperty("from_name", from.displayName());
        arguments.addProperty("to_lng", to.longitude());
        arguments.addProperty("to_lat", to.latitude());
        arguments.addProperty("to_name", to.displayName());
        JsonObject data = structuredContent(call("taxi_estimate", arguments));
        JsonArray items = data.getAsJsonArray("items");
        List<EstimateItem> values = new ArrayList<>();
        if (items != null) for (JsonElement element : items) {
            JsonObject item = element.getAsJsonObject();
            values.add(new EstimateItem(value(item, "productName"),
                    value(item, "productCategory"), value(item, "priceText")));
        }
        return new Estimate(value(data, "traceId"), List.copyOf(values));
    }

    public Order createOrder(String productCategory, String estimateTraceId) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("product_category", productCategory);
        arguments.addProperty("estimate_trace_id", estimateTraceId);
        JsonObject data = structuredContent(call("taxi_create_order", arguments));
        return new Order(value(data, "orderId"), value(data, "status"),
                objectValue(data, "from", "name"), objectValue(data, "to", "name"));
    }

    public RideAppLinks generateRideAppLink(Place from, Place to, String productCategory) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("from_lng", from.longitude());
        arguments.addProperty("from_lat", from.latitude());
        arguments.addProperty("to_lng", to.longitude());
        arguments.addProperty("to_lat", to.latitude());
        if (productCategory != null && !productCategory.isBlank()) {
            arguments.addProperty("product_category", productCategory);
        }
        JsonObject result = call("taxi_generate_ride_app_link", arguments);
        JsonObject structured = object(result, "structuredContent");
        RideAppLinks links = new RideAppLinks(value(structured, "appLink"),
                value(structured, "miniprogramLink"), value(structured, "browserLink"),
                value(structured, "deepLink"));
        if (!links.isEmpty()) return links;
        String text = contentText(result);
        String link = firstUrl(text);
        if (link.isBlank()) throw new IllegalStateException("滴滴未返回可打开的下单链接");
        return new RideAppLinks(link, "", "", "");
    }

    public OrderStatus queryOrder(String orderId) throws Exception {
        JsonObject arguments = new JsonObject();
        if (orderId != null && !orderId.isBlank()) arguments.addProperty("order_id", orderId);
        JsonObject data = structuredContent(call("taxi_query_order", arguments));
        JsonObject driver = object(data, "driver");
        JsonObject map = object(data, "map");
        return new OrderStatus(value(data, "orderId"), value(data, "statusCode"), value(data, "statusText"),
                value(driver, "name"), value(driver, "phone"), value(driver, "carModel"),
                value(driver, "carPlate"), value(map, "distanceKm"), value(map, "eta"));
    }

    public boolean cancelOrder(String orderId, String reason) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("order_id", orderId);
        if (reason != null && !reason.isBlank()) arguments.addProperty("reason", reason);
        JsonObject data = structuredContent(call("taxi_cancel_order", arguments));
        return data.has("success") && data.get("success").getAsBoolean();
    }

    public DriverLocation driverLocation(String orderId) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("order_id", orderId);
        JsonObject data = structuredContent(call("taxi_get_driver_location", arguments));
        return new DriverLocation(value(data, "longitude"), value(data, "latitude"));
    }

    public String reverseGeocode(DriverLocation location) throws Exception {
        JsonObject arguments = new JsonObject();
        arguments.addProperty("location", location.longitude() + "," + location.latitude());
        return contentText(call("maps_regeocode", arguments));
    }

    private JsonObject call(String toolName, JsonObject arguments) throws Exception {
        if (!isConfigured()) throw new IllegalStateException("未配置 DIDI_MCP_KEY");
        JsonObject params = new JsonObject();
        params.addProperty("name", toolName);
        params.add("arguments", arguments);
        JsonObject requestJson = new JsonObject();
        requestJson.addProperty("jsonrpc", "2.0");
        requestJson.addProperty("id", requestIds.getAndIncrement());
        requestJson.addProperty("method", "tools/call");
        requestJson.add("params", params);
        String endpoint = sandbox ? SANDBOX_ENDPOINT : PRODUCTION_ENDPOINT;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + encode(mcpKey)))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(requestJson))).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IllegalStateException("滴滴 Sandbox 暂时不可用（HTTP " + response.statusCode() + "）");
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        if (body.has("error")) throw new IllegalStateException("滴滴 Sandbox 调用失败：" + value(object(body, "error"), "message"));
        JsonObject result = object(body, "result");
        if (result == null) throw new IllegalStateException("滴滴 Sandbox 返回缺少 result");
        return result;
    }

    private JsonObject structuredContent(JsonObject result) {
        JsonObject structured = object(result, "structuredContent");
        if (structured == null) throw new IllegalStateException("滴滴 Sandbox 返回缺少结构化数据");
        return structured;
    }

    private String contentText(JsonObject result) {
        JsonArray content = result.getAsJsonArray("content");
        if (content == null || content.isEmpty()) throw new IllegalStateException("滴滴 Sandbox 返回缺少内容");
        return value(content.get(0).getAsJsonObject(), "text");
    }

    private static JsonObject object(JsonObject source, String name) {
        return source != null && source.has(name) && source.get(name).isJsonObject() ? source.getAsJsonObject(name) : null;
    }
    private static String objectValue(JsonObject source, String objectName, String propertyName) { return value(object(source, objectName), propertyName); }
    private static String value(JsonObject source, String name) { return source != null && source.has(name) && !source.get(name).isJsonNull() ? source.get(name).getAsString() : ""; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static String firstUrl(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) return firstUrl(value.getAsString());
        if (value.isJsonArray()) for (JsonElement item : value.getAsJsonArray()) {
            String link = firstUrl(item);
            if (!link.isBlank()) return link;
        }
        if (value.isJsonObject()) for (var entry : value.getAsJsonObject().entrySet()) {
            String link = firstUrl(entry.getValue());
            if (!link.isBlank()) return link;
        }
        return "";
    }

    private static String firstUrl(String text) {
        if (text == null) return "";
        Matcher matcher = URL_PATTERN.matcher(text);
        return matcher.find() ? trimUrlPunctuation(matcher.group()) : "";
    }

    private static String trimUrlPunctuation(String url) {
        String value = url;
        while (!value.isEmpty() && ")]},。；，!?！？”’".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    public record Place(String name, String address, String fullAddress, String city, String longitude, String latitude) {
        public String displayName() { return fullAddress.isBlank() ? name : fullAddress; }
        public String label() { return address.isBlank() ? displayName() : name + "（" + address + "）"; }
    }
    public record Estimate(String traceId, List<EstimateItem> items) { }
    public record EstimateItem(String productName, String productCategory, String priceText) { }
    public record Order(String orderId, String status, String fromName, String toName) { }
    public record OrderStatus(String orderId, String statusCode, String statusText, String driverName, String driverPhone, String carModel, String carPlate, String distanceKm, String eta) { }
    public record DriverLocation(String longitude, String latitude) { }
    public record RideAppLinks(String appLink, String miniprogramLink, String browserLink, String deepLink) {
        public boolean isEmpty() {
            return appLink.isBlank() && miniprogramLink.isBlank() && browserLink.isBlank() && deepLink.isBlank();
        }
    }
}
