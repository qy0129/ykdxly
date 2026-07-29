package com.example.ilink.adapter.inbound.http;

import com.example.ilink.application.conversation.ChatSession;
import com.example.ilink.application.conversation.SessionService;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.capabilities.memory.UserMemory;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 为本机管理员提供会话历史、长期资料和会话切换页面。 */
public final class SessionManagementServer implements AutoCloseable {

    private static final String PAGE_RESOURCE = "/templates/session-management.html";
    private static final String CSS_RESOURCE = "/static/css/session-management.css";
    private static final String JS_RESOURCE = "/static/js/session-management.js";
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionService sessionService;
    private final UserSessionStore sessions;
    private final MySqlStore database;
    private final String accessToken = UUID.randomUUID().toString().replace("-", "");
    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private volatile String ownerUserId = "";
    private HttpServer server;

    public SessionManagementServer(SessionService sessionService, UserSessionStore sessions, MySqlStore database) {
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.database = database;
    }

    public void start() {
        if (!Config.SESSION_MANAGEMENT_ENABLED) return;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    Config.SESSION_MANAGEMENT_BIND_ADDRESS, Config.SESSION_MANAGEMENT_PORT), 0);
            server.createContext("/", this::handle);
            server.setExecutor(executor);
            server.start();
            System.out.println("[会话管理] 已启动: " + url());
        } catch (IOException error) {
            System.err.println("[会话管理] 启动失败: " + error.getMessage());
            server = null;
        }
    }

    public String url() {
        if (server == null) return "";
        return "http://" + Config.SESSION_MANAGEMENT_BIND_ADDRESS + ":"
                + Config.SESSION_MANAGEMENT_PORT + "/sessions/" + accessToken;
    }

    /** 由微信消息入口刷新页面所管理的当前用户。 */
    public void useUser(String userId) {
        if (userId != null && !userId.isBlank()) ownerUserId = userId.trim();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String pagePath = "/sessions/" + accessToken;
            String apiPath = "/api/sessions/" + accessToken;
            if ("GET".equals(exchange.getRequestMethod()) && path.equals(pagePath)) {
                sendResource(exchange, 200, "text/html; charset=utf-8", PAGE_RESOURCE);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/static/css/session-management.css")) {
                sendResource(exchange, 200, "text/css; charset=utf-8", CSS_RESOURCE);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.equals("/static/js/session-management.js")) {
                sendResource(exchange, 200, "text/javascript; charset=utf-8", JS_RESOURCE);
                return;
            }

            if (path.equals(apiPath) && "GET".equals(exchange.getRequestMethod())) {
                sendJson(exchange, 200, overview());
                return;
            }
            if (path.equals(apiPath + "/create") && "POST".equals(exchange.getRequestMethod())) {
                String userId = requireOwnerUserId();
                JsonObject response = new JsonObject();
                response.addProperty("sessionId", sessionService.createNewSession(userId));
                sendJson(exchange, 201, response);
                return;
            }
            String filePrefix = apiPath + "/files/";
            if (path.startsWith(filePrefix) && "GET".equals(exchange.getRequestMethod())) {
                sendFile(exchange, requireOwnerUserId(), path.substring(filePrefix.length()));
                return;
            }
            String sessionPrefix = apiPath + "/";
            if (path.startsWith(sessionPrefix)) {
                String suffix = path.substring(sessionPrefix.length());
                if (suffix.endsWith("/messages") && "GET".equals(exchange.getRequestMethod())) {
                    String userId = requireOwnerUserId();
                    String sessionId = suffix.substring(0, suffix.length() - "/messages".length());
                    sendJson(exchange, 200, messages(userId, sessionId));
                    return;
                }
                if (suffix.endsWith("/activate") && "POST".equals(exchange.getRequestMethod())) {
                    String userId = requireOwnerUserId();
                    String sessionId = suffix.substring(0, suffix.length() - "/activate".length());
                    boolean switched = sessionService.switchSession(userId, sessionId);
                    JsonObject response = new JsonObject();
                    response.addProperty("success", switched);
                    sendJson(exchange, switched ? 200 : 404, response);
                    return;
                }
            }
            send(exchange, 404, "text/plain; charset=utf-8", "页面不存在".getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException error) {
            JsonObject response = new JsonObject();
            response.addProperty("error", error.getMessage());
            sendJson(exchange, 400, response);
        } catch (Exception error) {
            System.err.println("[会话管理] 请求失败: " + error.getMessage());
            JsonObject response = new JsonObject();
            response.addProperty("error", "暂时无法读取会话数据");
            sendJson(exchange, 500, response);
        } finally {
            exchange.close();
        }
    }

    private JsonObject overview() {
        JsonObject response = new JsonObject();
        response.addProperty("databaseAvailable", database.isAvailable());
        String userId = ownerUserId;
        response.addProperty("ready", !userId.isBlank());
        if (userId.isBlank()) {
            response.addProperty("message", "请先向 Bot 发送一条消息，再刷新此页面。");
            response.add("sessions", new JsonArray());
            response.add("memories", new JsonArray());
            response.add("files", new JsonArray());
            return response;
        }

        JsonObject profile = new JsonObject();
        profile.addProperty("persona", sessions.getPersonaName(userId));
        profile.addProperty("location", nullToEmpty(sessions.getCurrentLocation(userId)));
        ChatSession active = sessions.getActiveSession(userId);
        profile.addProperty("activeSessionId", active == null ? "" : active.sessionId());
        response.add("profile", profile);

        JsonArray memories = new JsonArray();
        for (UserMemory memory : database.loadMemories(userId)) {
            JsonObject item = new JsonObject();
            item.addProperty("type", nullToEmpty(memory.type()));
            item.addProperty("value", nullToEmpty(memory.value()));
            item.addProperty("importance", memory.importance());
            item.addProperty("updatedAt", format(memory.updatedAt()));
            memories.add(item);
        }
        response.add("memories", memories);

        JsonArray sessionRows = new JsonArray();
        for (MySqlStore.SessionRow row : sessionService.listSessions(userId)) {
            JsonObject item = new JsonObject();
            item.addProperty("sessionId", row.sessionId());
            item.addProperty("title", title(row));
            item.addProperty("status", nullToEmpty(row.status()));
            item.addProperty("lastActiveTime", format(row.lastActiveTime()));
            item.addProperty("createdTime", format(row.createdTime()));
            sessionRows.add(item);
        }
        response.add("sessions", sessionRows);
        response.add("files", files(userId));
        return response;
    }

    private JsonObject messages(String userId, String sessionId) {
        MySqlStore.SessionRow session = database.findSession(sessionId);
        if (session == null || !userId.equals(session.wechatId())) {
            throw new IllegalArgumentException("会话不存在或不属于该用户");
        }
        JsonObject response = new JsonObject();
        response.addProperty("sessionId", sessionId);
        response.addProperty("title", title(session));
        JsonArray items = new JsonArray();
        for (MySqlStore.ChatEntry entry : database.loadSessionMessages(sessionId, 200)) {
            JsonObject item = new JsonObject();
            item.addProperty("role", nullToEmpty(entry.role()));
            item.addProperty("content", nullToEmpty(entry.content()));
            items.add(item);
        }
        response.add("messages", items);
        return response;
    }

    private String requireOwnerUserId() {
        if (ownerUserId.isBlank()) throw new IllegalArgumentException("请先向 Bot 发送一条消息");
        return ownerUserId;
    }

    private JsonArray files(String userId) {
        JsonArray files = new JsonArray();
        Path userDirectory = userMediaDirectory(userId);
        if (!Files.isDirectory(userDirectory)) return files;
        try (var paths = Files.walk(userDirectory, 2)) {
            paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(this::lastModified).reversed())
                    .limit(100)
                    .forEach(path -> files.add(fileItem(userDirectory, path)));
        } catch (IOException error) {
            System.err.println("[会话管理] 读取用户文件失败: " + error.getMessage());
        }
        return files;
    }

    private JsonObject fileItem(Path userDirectory, Path path) {
        Path relative = userDirectory.relativize(path);
        JsonObject item = new JsonObject();
        item.addProperty("name", path.getFileName().toString());
        item.addProperty("type", relative.getNameCount() > 1 ? relative.getName(0).toString() : "file");
        item.addProperty("size", size(path));
        item.addProperty("modifiedAt", format(lastModified(path)));
        item.addProperty("url", "/api/sessions/" + accessToken + "/files/"
                + relative.toString().replace('\\', '/'));
        item.addProperty("preview", isImage(path));
        return item;
    }

    private void sendFile(HttpExchange exchange, String userId, String requestedPath) throws IOException {
        Path root = userMediaDirectory(userId);
        Path file = root.resolve(requestedPath).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=utf-8", "文件不存在".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = Files.readAllBytes(file);
        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\""
                + file.getFileName().toString().replace("\"", "") + "\"");
        send(exchange, 200, contentType(file), body);
    }

    private Path userMediaDirectory(String userId) {
        return Config.MEDIA_DIR.resolve(userId.replaceAll("[^a-zA-Z0-9._-]", "_")).toAbsolutePath().normalize();
    }

    private LocalDateTime lastModified(Path path) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(path).toInstant(), java.time.ZoneId.systemDefault());
        } catch (IOException error) {
            return LocalDateTime.MIN;
        }
    }

    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException error) {
            return 0L;
        }
    }

    private boolean isImage(Path path) {
        String extension = extension(path);
        return extension.equals("png") || extension.equals("jpg") || extension.equals("jpeg") || extension.equals("webp");
    }

    private String contentType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null) return detected;
        } catch (IOException ignored) {
            // 使用常见文件类型作为回退。
        }
        return switch (extension(path)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int index = name.lastIndexOf('.');
        return index < 0 ? "" : name.substring(index + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private String title(MySqlStore.SessionRow row) {
        if (row.title() != null && !row.title().isBlank()) return row.title();
        return "未命名会话";
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : TIME_FORMAT.format(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private void sendResource(HttpExchange exchange, int status, String contentType, String resource)
            throws IOException {
        try (InputStream input = SessionManagementServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                send(exchange, 404, "text/plain; charset=utf-8", "找不到页面资源".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(exchange, status, contentType, input.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
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
