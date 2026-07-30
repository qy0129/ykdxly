package com.example.ilink.adapter.inbound.http;

import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.location.LocationService;
import com.example.ilink.platform.network.CloudflareTunnel;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 提供手机定位授权页和经纬度提交接口。 */
public final class LocationHttpServer implements AutoCloseable {

    private static final int MAX_BODY_BYTES = 4096;

    private final LocationService locationService;
    private final int actualPort;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Gson gson = new Gson();
    private HttpServer server;
    private CloudflareTunnel tunnel;

    public LocationHttpServer(LocationService locationService) {
        this.locationService = locationService;
        this.actualPort = findAvailablePort(Config.LOCATION_BIND_ADDRESS, Config.LOCATION_PORT);
    }

    public void start() {
        if (!Config.LOCATION_ENABLED) return;
        try {
            server = HttpServer.create(new InetSocketAddress(Config.LOCATION_BIND_ADDRESS, actualPort), 0);
            server.createContext("/location/authorize/", this::handleAuthorize);
            server.createContext("/location/api/", this::handleSubmit);
            server.createContext("/location/static/", this::handleStatic);
            server.setExecutor(executor);
            server.start();
            configurePublicUrl();
            System.out.println("[位置] 授权服务已启动 port=" + actualPort);
        } catch (Exception error) {
            System.err.println("[位置] 授权服务启动失败: " + error.getMessage());
            close();
        }
    }

    private void configurePublicUrl() {
        if (!Config.LOCATION_BASE_URL.isBlank()) {
            locationService.useBaseUrl(Config.LOCATION_BASE_URL);
            return;
        }
        if (!Config.LOCATION_TUNNEL_ENABLED) {
            System.err.println("[位置] 未配置 HTTPS 公网地址，登录时不会发送定位链接");
            return;
        }
        tunnel = new CloudflareTunnel(
                Config.LOCATION_TUNNEL_COMMAND, actualPort, Config.LOCATION_TUNNEL_TIMEOUT);
        String publicUrl = tunnel.start();
        if (publicUrl.isBlank()) {
            tunnel.close();
            tunnel = null;
            System.err.println("[位置] HTTPS 隧道启动失败，登录时不会发送定位链接");
            return;
        }
        locationService.useBaseUrl(publicUrl);
        System.out.println("[位置] HTTPS 地址：" + publicUrl);
    }

    private void handleAuthorize(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String token = token(exchange, "/location/authorize/");
        if (!locationService.isTokenActive(token)) {
            respond(exchange, 410, resource("static/location/expired.html"), "text/html");
            return;
        }
        respond(exchange, 200, resource("static/location/index.html"), "text/html");
    }

    private void handleSubmit(HttpExchange exchange) {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "POST");
            return;
        }
        String token = token(exchange, "/location/api/");
        if (!locationService.isTokenActive(token)) {
            respondJson(exchange, 410, false, "定位链接已失效，请回到微信重新获取", null);
            return;
        }
        try {
            byte[] body = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length > MAX_BODY_BYTES) {
                respondJson(exchange, 413, false, "请求内容过大", null);
                return;
            }
            JsonObject json = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            double latitude = requiredDouble(json, "latitude");
            double longitude = requiredDouble(json, "longitude");
            Double accuracy = json.has("accuracy") && !json.get("accuracy").isJsonNull()
                    ? json.get("accuracy").getAsDouble() : null;
            LocationService.LocationUpdate update = locationService.submitGps(
                    token, latitude, longitude, accuracy);
            respondJson(exchange, 200, true, "位置已更新", update.address());
        } catch (IllegalArgumentException error) {
            respondJson(exchange, 400, false, error.getMessage(), null);
        } catch (Exception error) {
            System.err.println("[位置] 坐标处理失败: " + error.getMessage());
            respondJson(exchange, 502, false, "暂时无法识别当前位置，请稍后重试", null);
        }
    }

    private void handleStatic(HttpExchange exchange) {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            methodNotAllowed(exchange, "GET");
            return;
        }
        String relative = exchange.getRequestURI().getPath().substring("/location/static/".length());
        if (!relative.equals("location.css") && !relative.equals("location.js")) {
            respond(exchange, 404, "Not Found", "text/plain");
            return;
        }
        String contentType = relative.endsWith(".css") ? "text/css" : "application/javascript";
        respond(exchange, 200, resource("static/location/" + relative), contentType);
    }

    private double requiredDouble(JsonObject json, String name) {
        if (json == null || !json.has(name) || json.get(name).isJsonNull()) {
            throw new IllegalArgumentException("缺少" + name);
        }
        return json.get(name).getAsDouble();
    }

    private String token(HttpExchange exchange, String prefix) {
        String path = exchange.getRequestURI().getPath();
        if (!path.startsWith(prefix)) return "";
        String token = path.substring(prefix.length());
        return token.contains("/") ? "" : token;
    }

    private String resource(String path) {
        try (InputStream input = LocationHttpServer.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("缺少页面资源: " + path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "服务资源加载失败";
        }
    }

    private void respondJson(HttpExchange exchange, int code, boolean success,
                             String message, String address) {
        JsonObject json = new JsonObject();
        json.addProperty("success", success);
        json.addProperty("message", message == null ? "" : message);
        if (address != null) json.addProperty("address", address);
        respond(exchange, code, gson.toJson(json), "application/json");
    }

    private void methodNotAllowed(HttpExchange exchange, String allowed) {
        exchange.getResponseHeaders().set("Allow", allowed);
        respond(exchange, 405, "Method Not Allowed", "text/plain");
    }

    private void respond(HttpExchange exchange, int code, String body, String contentType) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Permissions-Policy", "geolocation=(self)");
            exchange.getResponseHeaders().set("Content-Security-Policy",
                    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; connect-src 'self'");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        } catch (Exception ignored) {
        } finally {
            exchange.close();
        }
    }

    private int findAvailablePort(String bindAddress, int start) {
        for (int port = start; port < start + 20; port++) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.bind(new InetSocketAddress(bindAddress, port));
                return port;
            } catch (Exception ignored) {
            }
        }
        return start;
    }

    @Override
    public void close() {
        if (tunnel != null) tunnel.close();
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }
}
