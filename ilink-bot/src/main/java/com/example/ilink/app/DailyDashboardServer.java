package com.example.ilink.app;

import com.example.ilink.config.Config;
import com.example.ilink.feature.express.ExpressPageRenderer;
import com.example.ilink.feature.express.ExpressPageService;
import com.google.gson.JsonObject;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 在 Bot 进程内提供单用户日报页面和待办完成接口。 */
public final class DailyDashboardServer implements AutoCloseable {

    private static final String PAGE_RESOURCE = "/templates/daily-dashboard.html";
    private static final String CSS_RESOURCE = "/static/css/daily-dashboard.css";
    private static final String JS_RESOURCE = "/static/js/daily-dashboard.js";
    private static final String EXPRESS_PAGE_RESOURCE = "/templates/express/detail.html";
    private static final String EXPRESS_CSS_RESOURCE = "/static/css/express.css";
    private static final String EXPRESS_JS_RESOURCE = "/static/js/express.js";
    private static final Path PUBLIC_URL_FILE = Path.of("data", "dashboard-public-url.txt");

    private final DailyDashboardService dashboardService;
    private final ExpressPageService expressPageService;
    private final ExpressPageRenderer expressPageRenderer = new ExpressPageRenderer();
    private final String accessToken = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private volatile String ownerUserId = "";
    private volatile String runtimeBaseUrl = "";
    private HttpServer server;
    private CloudflareTunnel tunnel;

    public DailyDashboardServer(DailyDashboardService dashboardService) {
        this(dashboardService, null);
    }

    public DailyDashboardServer(DailyDashboardService dashboardService,
                                ExpressPageService expressPageService) {
        this.dashboardService = dashboardService;
        this.expressPageService = expressPageService;
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
            saveDashboardUrl();
            if (expressPageService != null) expressPageService.activate(baseUrl());
            System.out.println("[日报页面] 已启动：" + url());
        } catch (Exception error) {
            System.err.println("[日报页面] 启动失败，继续使用文字简报: " + error.getMessage());
            server = null;
        }
    }

    public void useUser(String userId) {
        if (userId != null && !userId.isBlank()) ownerUserId = userId;
    }

    public String urlFor(String userId) {
        useUser(userId);
        return url();
    }

    public String url() {
        if (server == null) return "";
        return baseUrl() + "/daily/" + accessToken;
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

    private void saveDashboardUrl() {
        try {
            Files.createDirectories(PUBLIC_URL_FILE.getParent());
            Files.writeString(PUBLIC_URL_FILE, url(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // 状态文件只用于本机排查，不影响页面服务。
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/express/") && "GET".equals(exchange.getRequestMethod())) {
                if (handleExpress(exchange, path)) return;
            }
            if (path.equals("/static/css/express.css") && "GET".equals(exchange.getRequestMethod())) {
                sendResource(exchange, 200, "text/css; charset=utf-8", EXPRESS_CSS_RESOURCE);
                return;
            }
            if (path.equals("/static/js/express.js") && "GET".equals(exchange.getRequestMethod())) {
                sendResource(exchange, 200, "text/javascript; charset=utf-8", EXPRESS_JS_RESOURCE);
                return;
            }
            if (path.equals("/daily/" + accessToken) && "GET".equals(exchange.getRequestMethod())) {
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
            if (path.equals("/api/daily/" + accessToken) && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, dashboardService.snapshot(ownerUserId));
                return;
            }
            if (path.equals("/api/weather/" + accessToken) && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, dashboardService.weatherSnapshot(ownerUserId));
                return;
            }
            String completePrefix = "/api/todos/" + accessToken + "/";
            if (path.startsWith(completePrefix) && path.endsWith("/complete")
                    && "POST".equals(exchange.getRequestMethod())) {
                String todoId = path.substring(completePrefix.length(), path.length() - "/complete".length());
                boolean completed = dashboardService.completeTodo(ownerUserId, todoId);
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

    private boolean handleExpress(HttpExchange exchange, String path) throws IOException {
        if (expressPageService == null) return false;
        String mapPrefix = "/express/map/";
        if (path.startsWith(mapPrefix)) {
            String token = path.substring(mapPrefix.length());
            ExpressPageService.PageSnapshot page = expressPageService.get(token);
            if (page == null || !page.mapEligible() || (page.mapResolved() && !page.mapAvailable())) {
                send(exchange, 404, "text/plain; charset=utf-8", "地图不可用".getBytes(StandardCharsets.UTF_8));
                return true;
            }
            if (!page.mapResolved()) {
                send(exchange, 202, "text/plain; charset=utf-8", "地图生成中".getBytes(StandardCharsets.UTF_8));
                return true;
            }
            byte[] map = expressPageService.mapImage(token);
            send(exchange, 200, "image/png", map);
            return true;
        }
        String token = path.substring("/express/".length());
        if (token.contains("/")) return false;
        ExpressPageService.PageSnapshot page = expressPageService.get(token);
        String html = page == null ? expressPageRenderer.errorPage("页面不存在或已经过期")
                : expressPageRenderer.render(token, page);
        send(exchange, page == null ? 404 : 200, "text/html; charset=utf-8",
                html.getBytes(StandardCharsets.UTF_8));
        return true;
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
        if (expressPageService != null) expressPageService.deactivate();
        if (tunnel != null) tunnel.close();
        executor.shutdownNow();
    }
}
