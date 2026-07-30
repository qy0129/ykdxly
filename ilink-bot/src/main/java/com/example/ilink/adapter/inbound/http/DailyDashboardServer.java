package com.example.ilink.adapter.inbound.http;

import com.example.ilink.capabilities.dashboard.DailyDashboardService;
import com.example.ilink.platform.network.CloudflareTunnel;

import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalDate;
import java.time.LocalTime;

/** 在 Bot 进程内提供单用户日报页面和待办完成接口。 */
public final class DailyDashboardServer implements AutoCloseable {

    private static final String PAGE_RESOURCE = "/templates/daily-dashboard.html";
    private static final String CSS_RESOURCE = "/static/css/daily-dashboard.css";
    private static final String JS_RESOURCE = "/static/js/daily-dashboard.js";
    private static final Path PUBLIC_URL_FILE = Path.of("data", "dashboard-public-url.txt");

    private final DailyDashboardService dashboardService;
    private final Map<String, String> userTokens = new ConcurrentHashMap<>();
    private final Map<String, String> tokenUsers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private volatile String runtimeBaseUrl = "";
    private HttpServer server;
    private CloudflareTunnel tunnel;

    public DailyDashboardServer(DailyDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    /** 启动本地网页服务；端口占用时仅关闭页面能力，不影响 Bot 其他功能。 */
    public void start() {
        if (!Config.DAILY_DASHBOARD_ENABLED) return;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    Config.DAILY_DASHBOARD_BIND_ADDRESS, Config.DAILY_DASHBOARD_PORT), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
            runtimeBaseUrl = resolveBaseUrl();
            if (!Config.PERSONAL_OWNER_USER_ID.isBlank()) {
                saveDashboardUrl(urlFor(Config.PERSONAL_OWNER_USER_ID));
            }
            System.out.println("[日报页面] 已启动：" + baseUrl());
        } catch (Exception error) {
            System.err.println("[日报页面] 启动失败，继续使用文字简报: " + error.getMessage());
            server = null;
        }
    }

    public String urlFor(String userId) {
        if (server == null || userId == null || userId.isBlank()) return "";
        String normalized = userId.trim();
        String token = userTokens.computeIfAbsent(normalized, ignored -> {
            String created = UUID.randomUUID().toString().replace("-", "");
            tokenUsers.put(created, normalized);
            return created;
        });
        String value = baseUrl() + "/daily/" + token;
        saveDashboardUrl(value);
        return value;
    }

    public String url() {
        return Config.PERSONAL_OWNER_USER_ID.isBlank() ? "" : urlFor(Config.PERSONAL_OWNER_USER_ID);
    }

    private String baseUrl() {
        String base = runtimeBaseUrl;
        if (base.isBlank()) base = "http://" + localAddress() + ":" + Config.DAILY_DASHBOARD_PORT;
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String resolveBaseUrl() {
        String configured = Config.DAILY_DASHBOARD_PUBLIC_URL == null
                ? "" : Config.DAILY_DASHBOARD_PUBLIC_URL.trim();
        if (!configured.isBlank()) return configured;
        if (Config.DAILY_DASHBOARD_TUNNEL_ENABLED) {
            tunnel = new CloudflareTunnel(Config.DAILY_DASHBOARD_TUNNEL_COMMAND,
                    Config.DAILY_DASHBOARD_PORT, Config.DAILY_DASHBOARD_TUNNEL_TIMEOUT);
            String publicUrl = tunnel.start();
            if (!publicUrl.isBlank()) {
                System.out.println("[页面公网访问] Cloudflare 临时隧道已连接：" + publicUrl);
                return publicUrl;
            }
            System.err.println("[页面公网访问] 隧道启动失败，暂时使用局域网地址");
        }
        return "";
    }

    private void saveDashboardUrl(String value) {
        try {
            Files.createDirectories(PUBLIC_URL_FILE.getParent());
            Files.writeString(PUBLIC_URL_FILE, value, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 状态文件只用于本机排查，不影响页面服务。
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String pageToken = tokenAfter(path, "/daily/");
            if (!pageToken.isBlank() && tokenUsers.containsKey(pageToken)
                    && "GET".equals(exchange.getRequestMethod())) {
                sendResource(exchange, 200, "text/html; charset=utf-8", PAGE_RESOURCE);
                return;
            }
            if (path.equals("/static/css/daily-dashboard.css") && "GET".equals(exchange.getRequestMethod())) {
                sendResource(exchange, 200, "text/css; charset=utf-8", CSS_RESOURCE);
                return;
            }
            if (path.equals("/static/js/daily-dashboard.js") && "GET".equals(exchange.getRequestMethod())) {
                sendResource(exchange, 200, "text/javascript; charset=utf-8", JS_RESOURCE);
                return;
            }
            String dailyToken = tokenAfter(path, "/api/daily/");
            if (!dailyToken.isBlank() && tokenUsers.containsKey(dailyToken)
                    && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, dashboardService.snapshot(tokenUsers.get(dailyToken)));
                return;
            }
            String weatherToken = tokenAfter(path, "/api/weather/");
            if (!weatherToken.isBlank() && tokenUsers.containsKey(weatherToken)
                    && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, dashboardService.weatherSnapshot(tokenUsers.get(weatherToken)));
                return;
            }
            String todoToken = tokenOnly(path, "/api/todos/");
            if (!todoToken.isBlank() && tokenUsers.containsKey(todoToken)
                    && "POST".equals(exchange.getRequestMethod())) {
                JsonObject input = readJson(exchange);
                var todo = dashboardService.createTodo(
                        tokenUsers.get(todoToken),
                        string(input, "title"),
                        LocalDate.parse(string(input, "date")),
                        optionalTime(input, "time"),
                        integer(input, "reminderMinutes", 0));
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("id", todo.id());
                sendJson(exchange, 201, response);
                return;
            }
            String planToken = tokenOnly(path, "/api/plan-tasks/");
            if (!planToken.isBlank() && tokenUsers.containsKey(planToken)
                    && "POST".equals(exchange.getRequestMethod())) {
                JsonObject input = readJson(exchange);
                var task = dashboardService.createPlanTask(
                        tokenUsers.get(planToken),
                        string(input, "title"),
                        string(input, "description"),
                        LocalDate.parse(string(input, "date")),
                        integer(input, "estimatedMinutes", 30),
                        stringOrDefault(input, "priority", "medium"));
                JsonObject response = new JsonObject();
                response.addProperty("success", true);
                response.addProperty("id", task.id());
                sendJson(exchange, 201, response);
                return;
            }
            String[] todoParts = actionParts(path, "/api/todos/");
            if (todoParts != null && tokenUsers.containsKey(todoParts[0])
                    && "POST".equals(exchange.getRequestMethod())) {
                boolean completed = dashboardService.completeTodo(tokenUsers.get(todoParts[0]), todoParts[1]);
                JsonObject response = new JsonObject();
                response.addProperty("success", completed);
                sendJson(exchange, completed ? 200 : 404, response);
                return;
            }
            String[] planParts = actionParts(path, "/api/plan-tasks/");
            if (planParts != null && tokenUsers.containsKey(planParts[0])
                    && "POST".equals(exchange.getRequestMethod())) {
                boolean completed = dashboardService.completePlanTask(tokenUsers.get(planParts[0]), planParts[1]);
                JsonObject response = new JsonObject();
                response.addProperty("success", completed);
                sendJson(exchange, completed ? 200 : 404, response);
                return;
            }
            send(exchange, 404, "text/plain; charset=utf-8", "页面不存在".getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            JsonObject response = new JsonObject();
            response.addProperty("error", "日报页面暂时无法读取数据");
            sendJson(exchange, 500, response);
        } finally {
            exchange.close();
        }
    }

    private String tokenAfter(String path, String prefix) {
        if (!path.startsWith(prefix)) return "";
        String value = path.substring(prefix.length());
        return value.isBlank() || value.contains("/") ? "" : value;
    }

    private String[] actionParts(String path, String prefix) {
        if (!path.startsWith(prefix) || !path.endsWith("/complete")) return null;
        String value = path.substring(prefix.length(), path.length() - "/complete".length());
        int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) return null;
        return new String[]{value.substring(0, separator), value.substring(separator + 1)};
    }

    private String tokenOnly(String path, String prefix) {
        if (!path.startsWith(prefix)) return "";
        String value = path.substring(prefix.length());
        return value.isBlank() || value.contains("/") ? "" : value;
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) throw new IllegalArgumentException("请求内容不能为空");
        return JsonParser.parseString(body).getAsJsonObject();
    }

    private String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString().trim() : "";
    }

    private String stringOrDefault(JsonObject object, String name, String fallback) {
        String value = string(object, name);
        return value.isBlank() ? fallback : value;
    }

    private int integer(JsonObject object, String name, int fallback) {
        try {
            return object.has(name) ? object.get(name).getAsInt() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private LocalTime optionalTime(JsonObject object, String name) {
        String value = string(object, name);
        return value.isBlank() ? null : LocalTime.parse(value);
    }

    private void sendResource(HttpExchange exchange, int status, String contentType, String resource)
            throws IOException {
        try (InputStream input = DailyDashboardServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                send(exchange, 404, "text/plain; charset=utf-8",
                        ("找不到页面资源: " + resource).getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, status, contentType, input.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject value) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                value.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    private String localAddress() {
        // UDP connect 不会发送数据，只让系统选择默认路由对应的本地网卡。
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 53);
            String routedAddress = socket.getLocalAddress().getHostAddress();
            if (!routedAddress.isBlank() && !routedAddress.startsWith("127.")) return routedAddress;
        } catch (Exception ignored) {
            // 无默认路由时再枚举局域网地址。
        }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            return java.util.Collections.list(interfaces).stream()
                    .filter(network -> {
                        try {
                            return network.isUp() && !network.isLoopback() && !network.isVirtual();
                        } catch (Exception ignored) {
                            return false;
                        }
                    })
                    .flatMap(network -> java.util.Collections.list(network.getInetAddresses()).stream())
                    .filter(address -> address instanceof Inet4Address && address.isSiteLocalAddress())
                    .sorted(Comparator.comparing(address -> address.getHostAddress().startsWith("192.168.") ? 0 : 1))
                    .map(address -> address.getHostAddress())
                    .findFirst().orElse("127.0.0.1");
        } catch (Exception ignored) {
            return "127.0.0.1";
        }
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        if (tunnel != null) tunnel.close();
        executor.shutdownNow();
    }
}
