package com.example.ilink.feature.express;

import com.example.ilink.feature.express.ExpressService.ExpressResult;
import com.example.ilink.feature.express.ExpressService.TrackingItem;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

public final class ExpressHttpServer {

    private final ExpressPageService pageService;
    private final ExpressPageRenderer renderer;
    private HttpServer server;

    public ExpressHttpServer(ExpressPageService pageService) {
        this.pageService = pageService;
        this.renderer = new ExpressPageRenderer();
    }

    public void start() {
        int port = pageService.actualPort;
        try {
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/express/view/", new ViewHandler());
            server.createContext("/express/api/", new ApiHandler());
            server.createContext("/express/qrcode/", new QrHandler());
            server.createContext("/express/static/", new StaticHandler());
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            System.out.println("[快递H5] server started port=" + port);
        } catch (Exception e) {
            System.err.println("[快递H5] 启动失败: " + e.getMessage());
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    private String extractToken(String path) {
        int idx = path.lastIndexOf('/');
        if (idx < 0) return "";
        String token = path.substring(idx + 1);
        int qidx = token.indexOf('?');
        if (qidx > 0) token = token.substring(0, qidx);
        return token;
    }

    private void respond(HttpExchange exchange, int code, String body, String contentType) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
            exchange.sendResponseHeaders(code, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } catch (Exception ignored) {
        }
    }

    private void respondBytes(HttpExchange exchange, int code, byte[] data, String contentType) {
        try {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(code, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        } catch (Exception ignored) {
        }
    }

    class ViewHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                String token = extractToken(exchange.getRequestURI().getPath());
                ExpressResult result = pageService.getResult(token);
                if (result == null) {
                    respond(exchange, 404, renderer.notFoundPage(), "text/html");
                    return;
                }
                String html = renderer.render(token, result);
                respond(exchange, 200, html, "text/html");
            } catch (Exception e) {
                respond(exchange, 500, renderer.errorPage("服务器内部错误: " + e.getMessage()), "text/html");
            }
        }
    }

    class ApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                String token = extractToken(exchange.getRequestURI().getPath());
                ExpressResult result = pageService.getResult(token);
                if (result == null) {
                    respond(exchange, 404, "{\"error\":\"not found\"}", "application/json");
                    return;
                }
                JsonObject json = new JsonObject();
                json.addProperty("company", nullSafe(result.courierName()));
                json.addProperty("expressNo", nullSafe(result.trackingNo()));
                json.addProperty("state", nullSafe(result.state()));
                json.addProperty("stateText", renderer.stateText(result.state()));
                json.addProperty("message", nullSafe(result.message()));
                JsonArray arr = new JsonArray();
                List<TrackingItem> items = result.items();
                if (items != null) {
                    for (TrackingItem item : items) {
                        JsonObject o = new JsonObject();
                        o.addProperty("time", nullSafe(item.time()));
                        o.addProperty("ftime", nullSafe(item.ftime()));
                        o.addProperty("context", nullSafe(item.context()));
                        arr.add(o);
                    }
                }
                json.add("data", arr);
                respond(exchange, 200, new Gson().toJson(json), "application/json");
            } catch (Exception e) {
                respond(exchange, 500, "{\"error\":\"" + esc(e.getMessage()) + "\"}", "application/json");
            }
        }
    }

    class QrHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            String token = extractToken(exchange.getRequestURI().getPath());
            String url = "http://localhost:" + pageService.actualPort + "/express/view/" + token;
            byte[] qr = pageService.generateQrCode(url, 300);
            if (qr.length == 0) {
                respond(exchange, 500, "生成二维码失败", "text/plain");
                return;
            }
            respondBytes(exchange, 200, qr, "image/png");
        }
    }

    class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) {
            try {
                String path = exchange.getRequestURI().getPath();
                // /express/static/css/express.css or /express/static/js/express.js
                String resourcePath = "static" + path; // e.g. "static/express/static/css/express.css"
                // Fix: path is /express/static/css/express.css → need static/express/css/express.css
                // Actually path starts with /express/static/ so strip the first /express/static prefix
                String relativePath = path.substring("/express/static/".length());
                String classpathResource = "static/express/" + relativePath;
                try (InputStream is = ExpressHttpServer.class.getClassLoader()
                        .getResourceAsStream(classpathResource)) {
                    if (is == null) {
                        respond(exchange, 404, "Not Found", "text/plain");
                        return;
                    }
                    byte[] data = is.readAllBytes();
                    String contentType;
                    if (relativePath.endsWith(".css")) {
                        contentType = "text/css";
                    } else if (relativePath.endsWith(".js")) {
                        contentType = "application/javascript";
                    } else {
                        contentType = "application/octet-stream";
                    }
                    respondBytes(exchange, 200, data, contentType);
                }
            } catch (Exception e) {
                respond(exchange, 500, "Internal Error", "text/plain");
            }
        }
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
