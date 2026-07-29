package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.executive.ExecutionLog;
import com.example.ilink.application.executive.ExecutiveRuntime;
import com.example.ilink.application.executive.ExecutiveStep;
import com.example.ilink.application.executive.ExecutiveTask;
import com.example.ilink.bootstrap.Config;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 仅本机可访问的 Executive 任务、步骤、日志和审批控制台。 */
public final class AutomationConsoleServer implements AutoCloseable {
    private static final String PAGE = "/templates/automation-console.html";
    private static final String CSS = "/static/css/automation-console.css";
    private static final String JS = "/static/js/automation-console.js";
    private final ExecutiveRuntime runtime;
    private final String token = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private HttpServer server;

    public AutomationConsoleServer(ExecutiveRuntime runtime) {
        this.runtime = runtime;
    }

    public void start() {
        if (!Config.AUTOMATION_CONSOLE_ENABLED) return;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    Config.AUTOMATION_CONSOLE_BIND_ADDRESS, Config.AUTOMATION_CONSOLE_PORT), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
            System.out.println("[Automation 控制台] 已启动：" + url());
        } catch (IOException error) {
            System.err.println("[Automation 控制台] 启动失败：" + error.getMessage());
            server = null;
        }
    }

    public String url() {
        return server == null ? "" : "http://" + Config.AUTOMATION_CONSOLE_BIND_ADDRESS + ":"
                + Config.AUTOMATION_CONSOLE_PORT + "/automation/" + token;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String pagePath = "/automation/" + token;
            String api = "/api/automation/" + token + "/tasks";
            if ("GET".equals(exchange.getRequestMethod()) && path.equals(pagePath)) {
                sendResource(exchange, "text/html; charset=utf-8", PAGE);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/static/css/automation-console.css")) {
                sendResource(exchange, "text/css; charset=utf-8", CSS);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/static/js/automation-console.js")) {
                sendResource(exchange, "text/javascript; charset=utf-8", JS);
                return;
            }
            if (path.equals(api) && "GET".equals(exchange.getRequestMethod())) {
                JsonArray tasks = new JsonArray();
                for (ExecutiveTask task : runtime.listTasks("")) tasks.add(taskJson(task));
                sendJson(exchange, 200, tasks);
                return;
            }
            if (path.startsWith(api + "/")) {
                String suffix = path.substring((api + "/").length());
                String[] parts = suffix.split("/");
                String taskId = parts[0].toUpperCase();
                if (parts.length == 1 && "GET".equals(exchange.getRequestMethod())) {
                    ExecutiveRuntime.TaskDetails details = runtime.details(taskId);
                    if (details == null) {
                        error(exchange, 404, "任务不存在");
                    } else {
                        sendJson(exchange, 200, detailsJson(details));
                    }
                    return;
                }
                if (parts.length == 2 && "POST".equals(exchange.getRequestMethod())) {
                    String result = action(taskId, parts[1]);
                    JsonObject response = new JsonObject();
                    response.addProperty("message", result);
                    sendJson(exchange, 200, response);
                    return;
                }
            }
            error(exchange, 404, "页面不存在");
        } catch (Exception error) {
            System.err.println("[Automation 控制台] 请求失败：" + error.getMessage());
            error(exchange, 500, "控制台暂时无法处理请求");
        } finally {
            exchange.close();
        }
    }

    private String action(String taskId, String action) {
        ExecutiveRuntime.TaskDetails details = runtime.details(taskId);
        if (details == null) return "任务不存在";
        return switch (action) {
            case "approve" -> runtime.decideTask(taskId, true);
            case "reject" -> runtime.decideTask(taskId, false);
            case "cancel" -> runtime.handleCommand(details.task().userId(), "取消任务 " + taskId);
            case "retry" -> runtime.handleCommand(details.task().userId(), "重试任务 " + taskId);
            default -> "不支持的操作";
        };
    }

    private JsonObject detailsJson(ExecutiveRuntime.TaskDetails details) {
        JsonObject response = new JsonObject();
        response.add("task", taskJson(details.task()));
        JsonArray steps = new JsonArray();
        for (ExecutiveStep step : details.steps()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", step.id());
            item.addProperty("sequence", step.sequence());
            item.addProperty("title", step.title());
            item.addProperty("tool", step.toolName());
            item.addProperty("status", step.status().name());
            item.addProperty("attempts", step.attempts());
            item.addProperty("output", step.outputText());
            item.addProperty("error", step.lastError());
            steps.add(item);
        }
        response.add("steps", steps);
        JsonArray logs = new JsonArray();
        for (ExecutionLog log : details.logs()) {
            JsonObject item = new JsonObject();
            item.addProperty("time", time(log.createdAt()));
            item.addProperty("event", log.eventType());
            item.addProperty("status", log.status());
            item.addProperty("message", log.message());
            logs.add(item);
        }
        response.add("logs", logs);
        return response;
    }

    private JsonObject taskJson(ExecutiveTask task) {
        JsonObject item = new JsonObject();
        item.addProperty("id", task.id());
        item.addProperty("userId", task.userId());
        item.addProperty("goal", task.goal());
        item.addProperty("status", task.status().name());
        item.addProperty("priority", task.priority());
        item.addProperty("currentStep", task.currentStep());
        item.addProperty("nextRunAt", time(task.nextRunAt()));
        item.addProperty("updatedAt", time(task.updatedAt()));
        item.addProperty("error", task.lastError());
        return item;
    }

    private String time(LocalDateTime value) {
        return value == null ? "" : value.toString();
    }

    private void sendResource(HttpExchange exchange, String contentType, String resource) throws IOException {
        try (InputStream input = AutomationConsoleServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                error(exchange, 404, "页面资源不存在");
                return;
            }
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int status, com.google.gson.JsonElement body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void error(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        sendJson(exchange, status, body);
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        if (server != null) server.stop(0);
        executor.shutdownNow();
    }
}
