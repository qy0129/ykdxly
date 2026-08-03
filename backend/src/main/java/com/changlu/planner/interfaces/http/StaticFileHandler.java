package com.changlu.planner.interfaces.http;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 只负责托管前端静态文件。
 *
 * API 路由和静态文件路由共享同一个 HttpServer，但不应共享处理逻辑；
 * 这样前端构建目录变化时，不会影响计划业务接口。
 */
final class StaticFileHandler implements HttpHandler {
  private final Gson gson = new Gson();

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    String requestPath = exchange.getRequestURI().getPath();
    if (requestPath.startsWith("/api")) {
      json(exchange, 404, Map.of("error", "not_found"));
      return;
    }
    if ("OPTIONS".equals(exchange.getRequestMethod())) {
      cors(exchange);
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
      json(exchange, 405, Map.of("error", "method_not_allowed"));
      return;
    }

    Path root = Paths.get(System.getenv().getOrDefault("PLANNER_WEB_DIR", "D:\\.CC\\web\\dist"))
        .toAbsolutePath().normalize();
    String decoded = URLDecoder.decode(requestPath, StandardCharsets.UTF_8);
    Path requested = root.resolve(decoded.substring(1)).normalize();
    if (!requested.startsWith(root)) {
      json(exchange, 400, Map.of("error", "invalid_path"));
      return;
    }
    Path file = Files.isRegularFile(requested) ? requested : root.resolve("index.html");
    if (!Files.isRegularFile(file)) {
      json(exchange, 404, Map.of("error", "web_not_built"));
      return;
    }

    byte[] content = Files.readAllBytes(file);
    cors(exchange);
    exchange.getResponseHeaders().set("Content-Type", mimeType(file));
    exchange.getResponseHeaders().set("Cache-Control",
        file.getFileName().toString().equals("index.html") ? "no-cache" : "public, max-age=3600");
    if ("HEAD".equals(exchange.getRequestMethod())) {
      exchange.sendResponseHeaders(200, -1);
      exchange.close();
      return;
    }
    exchange.sendResponseHeaders(200, content.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(content);
    }
  }

  private void json(HttpExchange exchange, int status, Object value) throws IOException {
    cors(exchange);
    byte[] body = gson.toJson(value).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, body.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(body);
    }
  }

  private void cors(HttpExchange exchange) {
    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
    exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, X-Workspace-Id, X-User-Id");
    exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
  }

  private String mimeType(Path file) {
    String name = file.getFileName().toString().toLowerCase();
    if (name.endsWith(".html")) return "text/html; charset=utf-8";
    if (name.endsWith(".js")) return "text/javascript; charset=utf-8";
    if (name.endsWith(".css")) return "text/css; charset=utf-8";
    if (name.endsWith(".json")) return "application/json; charset=utf-8";
    if (name.endsWith(".svg")) return "image/svg+xml";
    if (name.endsWith(".png")) return "image/png";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
    if (name.endsWith(".woff2")) return "font/woff2";
    return "application/octet-stream";
  }
}
