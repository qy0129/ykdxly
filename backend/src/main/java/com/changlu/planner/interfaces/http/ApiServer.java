package com.changlu.planner.interfaces.http;

import com.changlu.planner.agent.core.AgentFacade;
import com.changlu.planner.agent.core.ModelClient;
import com.changlu.planner.agent.subagents.briefing.BriefingResult;
import com.changlu.planner.agent.subagents.document.DocumentResult;
import com.changlu.planner.agent.subagents.travel.external.TravelApiClient;
import com.changlu.planner.agent.subagents.travel.services.AmapWeatherForecastService;
import com.changlu.planner.features.briefing.ScheduleMaterialService;
import com.changlu.planner.features.export.StatsPdfGenerator;
import com.changlu.planner.features.learning.LearningService;
import com.changlu.planner.features.notes.NotePrompt;
import com.changlu.planner.features.plan.PlanExecutionService;
import com.changlu.planner.features.reminder.ReminderService;
import com.changlu.planner.agent.subagents.image.tools.ImageAssetStore;
import com.changlu.planner.shared.database.Database;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** HTTP 适配层：负责把外部请求转换为现有业务方法，不保存独立的业务状态。 */
public final class ApiServer {
  private static final Logger LOG = LoggerFactory.getLogger(ApiServer.class);
  private static final String RESPONSE_STATUS_ATTRIBUTE = "responseStatus";
  private static final int MAX_AGENT_FILE_BYTES = 25 * 1024 * 1024;
  private final Database database;
  private final int port;
  private final Gson gson = new Gson();
  private final AgentFacade agent;
  private final ModelClient model;
  private final PlanExecutionService planExecution;
  private final ReminderService reminders;
  private final ScheduleMaterialService scheduleMaterials;
  private final LearningService learningService;
  private final StaticFileHandler staticFiles = new StaticFileHandler();
  private final HttpClient imageClient = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(15)).build();
  private HttpServer server;
  private ExecutorService httpExecutor;
  /** 日历天气缓存（Amap 有配额，30 分钟内不重复请求）。 */
  private volatile JsonObject weatherCache;
  private volatile long weatherCacheAt;

  public ApiServer(Database database, int port) { this.database = database; this.port = port; this.agent = new AgentFacade(database); this.model = new ModelClient(); this.planExecution = new PlanExecutionService(database); this.reminders = new ReminderService(database, model, ctx -> { try { return agent.memoryContext(ctx); } catch (Exception ignored) { return ""; } }); this.scheduleMaterials = new ScheduleMaterialService(database); this.learningService = new LearningService(database); }
  public int port() { return port; }

  public void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
    // 路由按外部能力分组；静态文件由独立 handler 托管，避免和 API 逻辑混在一起。
    server.createContext("/api/health", logged(this::health));
    server.createContext("/api/profile", logged(this::profile));
    server.createContext("/api/weather/daily", logged(this::weatherDaily));
    server.createContext("/api/plans", logged(exchange -> crud(exchange, "plans")));
    server.createContext("/api/plans/", logged(this::planSubresource));
    server.createContext("/api/schedules", logged(exchange -> crud(exchange, "schedule_items")));
    server.createContext("/api/schedules/", logged(this::scheduleSubresource));
    server.createContext("/api/todos", logged(exchange -> crud(exchange, "todos")));
    server.createContext("/api/reminders/due", logged(this::dueReminders));
    server.createContext("/api/tasks", logged(this::tasks));
    server.createContext("/api/planning/preferences", logged(this::planningPreferences));
    server.createContext("/api/trash", logged(this::trash));
    server.createContext("/api/notes", logged(this::notes));
    server.createContext("/api/notes/generate", logged(this::noteGenerate));
    server.createContext("/api/notes/images", logged(this::noteImageUpload));
    server.createContext("/api/ai/review/chat", logged(this::aiReviewChat));
    server.createContext("/api/ai/images/", logged(this::aiImage));
    server.createContext("/api/ai/commands", logged(this::aiCommand));
    server.createContext("/api/ai/drafts/", logged(this::aiDraft));
    server.createContext("/api/ai/session", logged(this::aiSession));
    server.createContext("/api/ai/conversations", logged(this::aiConversations));
    server.createContext("/api/ai/conversations/", logged(this::aiConversation));
    server.createContext("/api/ai/memories", logged(this::aiMemories));
    server.createContext("/api/ai/memories/", logged(this::aiMemory));
    server.createContext("/api/ai/change-sets/", logged(this::aiChangeSet));
    server.createContext("/api/agent/runs", logged(this::agentRuns));
    server.createContext("/api/agent/runs/", logged(this::agentRun));
    server.createContext("/api/agent/drafts/", logged(this::agentDraft));
    server.createContext("/api/agent/files", logged(this::agentFiles));
    server.createContext("/api/review/facts", logged(this::reviewFacts));
    server.createContext("/api/review/today", logged(this::reviewToday));
    server.createContext("/api/learning/goals", logged(this::learningGoals));
    server.createContext("/api/learning/goals/", logged(this::learningGoal));
    server.createContext("/api/stats", logged(this::stats));
    server.createContext("/api/export/xlsx", logged(this::excelExport));
    server.createContext("/api/export/pdf", logged(this::pdfExport));
    server.createContext("/api/integrations/wechat/capture", logged(this::wechatCapture));
    server.createContext("/api/integrations/wechat/command", logged(this::wechatCommand));
    server.createContext("/api/integrations/wechat/ai", logged(this::wechatAiCommand));
    server.createContext("/api/integrations/wechat/ai/", logged(this::wechatAiDraft));
    server.createContext("/api/integrations/wechat/briefing", logged(this::wechatBriefing));
    server.createContext("/", staticFiles);
    httpExecutor = Executors.newFixedThreadPool(8);
    server.setExecutor(httpExecutor);
    server.start();
  }
  public void stop() {
    if (server != null) server.stop(0);
    if (httpExecutor != null) httpExecutor.shutdownNow();
    agent.close();
  }

  private HttpHandler logged(HttpHandler handler) {
    return exchange -> {
      if ("OPTIONS".equals(exchange.getRequestMethod())) {
        handler.handle(exchange);
        return;
      }
      String requestId = UUID.randomUUID().toString().substring(0, 8);
      long startedAt = System.nanoTime();
      MDC.put("requestId", requestId);
      exchange.getResponseHeaders().set("X-Request-Id", requestId);
      try {
        handler.handle(exchange);
      } catch (IOException | RuntimeException error) {
        LOG.error("[请求失败] {} {} 原因={}", exchange.getRequestMethod(), exchange.getRequestURI(), error.getMessage());
        throw error;
      } finally {
        // 常规请求不再逐条输出，工作流日志只记录 Agent 的关键节点。
        MDC.remove("requestId");
      }
    };
  }

  private String requestUser(HttpExchange exchange) {
    String wechatUser = exchange.getRequestHeaders().getFirst("X-Wechat-User-Id");
    if (wechatUser != null && !wechatUser.isBlank()) return "wechat:" + wechatUser;
    String user = exchange.getRequestHeaders().getFirst("X-User-Id");
    return user == null || user.isBlank() ? Database.DEFAULT_USER_ID.toString() : user;
  }

  private String requestWorkspace(HttpExchange exchange) {
    String workspace = exchange.getRequestHeaders().getFirst("X-Workspace-Id");
    return workspace == null || workspace.isBlank() ? Database.DEFAULT_WORKSPACE_ID.toString() : workspace;
  }

  private void dueReminders(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, reminders.dueForWeb(context(e)));
    } catch (SQLException ex) {
      LOG.error("[提醒查询失败] 原因={}", ex.getMessage(), ex);
      json(e, 500, Map.of("error", "database_error", "message", ex.getMessage()));
    }
  }

  private void health(HttpExchange e) throws IOException { json(e, 200, Map.of("ok", true, "service", "changlu-planner")); }

  /** 日历用的逐日天气 + 出行建议：复用 Amap 天气服务，默认城市取配置，内存缓存 30 分钟。 */
  private void weatherDaily(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      long now = System.currentTimeMillis();
      if (weatherCache != null && now - weatherCacheAt < 30 * 60 * 1000L) {
        json(e, 200, weatherCache); return;
      }
      String location = com.changlu.planner.shared.config.EnvironmentConfig
          .value("BRIEFING_DEFAULT_LOCATION", "briefing.default.location", "杭州");
      JsonObject request = new JsonObject();
      request.addProperty("destination", location);
      AmapWeatherForecastService weather = new AmapWeatherForecastService(new TravelApiClient());
      JsonObject forecast = weather.forecast(request, "calendar-weather");
      JsonArray days = new JsonArray();
      for (JsonElement element : array(forecast, "weather")) {
        if (!element.isJsonObject()) continue;
        JsonObject day = element.getAsJsonObject();
        JsonObject row = new JsonObject();
        row.addProperty("date", text(day, "date", ""));
        row.addProperty("condition", text(day, "condition", ""));
        if (day.has("tempHigh") && !day.get("tempHigh").isJsonNull()) row.add("tempHigh", day.get("tempHigh"));
        if (day.has("tempLow") && !day.get("tempLow").isJsonNull()) row.add("tempLow", day.get("tempLow"));
        Double high = day.has("tempHigh") && day.get("tempHigh").isJsonPrimitive() && day.get("tempHigh").getAsJsonPrimitive().isNumber()
            ? day.get("tempHigh").getAsDouble() : null;
        row.addProperty("suggestion", travelSuggestion(text(day, "condition", ""), high));
        days.add(row);
      }
      JsonObject result = new JsonObject();
      result.addProperty("location", location);
      result.add("days", days);
      weatherCache = result;
      weatherCacheAt = now;
      json(e, 200, result);
    } catch (Exception error) {
      LOG.warn("[日历天气获取失败] 原因={}", error.getMessage());
      json(e, 200, Map.of("location", "", "days", new JsonArray()));
    }
  }

  /** 根据天气与高温推导一句话出行建议（先判雨雪，再看温度极端）。 */
  private String travelSuggestion(String condition, Double tempHigh) {
    String c = condition == null ? "" : condition;
    if (c.contains("雷") || c.contains("暴雨") || c.contains("大雨") || c.contains("中雨")) return "带伞·少外出";
    if (c.contains("雨") || c.contains("阵雨")) return "带伞";
    if (c.contains("雪")) return "添衣防滑";
    if (c.contains("雾") || c.contains("霾")) return "注意防护";
    if (c.contains("晴")) return "适宜户外";
    if (c.contains("多云")) return "适宜出行";
    if (c.contains("阴")) return "宜室内活动";
    if (tempHigh != null && tempHigh >= 33) return "注意防暑";
    if (tempHigh != null && tempHigh <= 5) return "注意保暖";
    return "正常出行";
  }

  private JsonArray array(JsonObject object, String name) {
    return object.has(name) && object.get(name).isJsonArray() ? object.getAsJsonArray(name) : new JsonArray();
  }

  private String text(JsonObject object, String name, String fallback) {
    return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : fallback;
  }

  private void profile(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if ("GET".equals(e.getRequestMethod())) {
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "SELECT display_name, COALESCE(avatar_url, '') avatar_url FROM users WHERE id = ?")) {
          p.setBytes(1, Database.uuidBytes(user(e)));
          try (ResultSet rs = p.executeQuery()) {
            if (!rs.next()) { json(e, 404, Map.of("error", "user_not_found")); return; }
            json(e, 200, Map.of("displayName", rs.getString("display_name"), "avatarUrl", rs.getString("avatar_url")));
          }
        }
        return;
      }
      if ("PUT".equals(e.getRequestMethod()) || "POST".equals(e.getRequestMethod())) {
        JsonObject body = body(e);
        String displayName = string(body, "displayName", "").trim();
        if (displayName.isBlank()) { json(e, 400, Map.of("error", "display_name_required")); return; }
        String avatarUrl = string(body, "avatarUrl", "").trim();
        if (avatarUrl.length() > 3_000_000) { json(e, 400, Map.of("error", "avatar_image_too_large", "message", "头像图片不能超过 2 MB")); return; }
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
            "UPDATE users SET display_name = ?, avatar_url = ? WHERE id = ?")) {
          p.setString(1, displayName); p.setString(2, avatarUrl); p.setBytes(3, Database.uuidBytes(user(e)));
          if (p.executeUpdate() == 0) { json(e, 404, Map.of("error", "user_not_found")); return; }
        }
        json(e, 200, Map.of("displayName", displayName, "avatarUrl", avatarUrl));
        return;
      }
      json(e, 405, Map.of("error", "method_not_allowed"));
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void aiReviewChat(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, agent.start(body(e), context(e), "web"));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (IllegalStateException ex) { json(e, 503, Map.of("error", "ai_unavailable", "message", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "ai_error", "message", ex.getMessage())); }
  }

  private void aiCommand(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, agent.start(body(e), context(e), "web"));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (IllegalStateException ex) { json(e, 503, Map.of("error", "ai_unavailable", "message", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "ai_command_error", "message", ex.getMessage())); }
  }

  private void aiDraft(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 5 || parts[4].isBlank()) { json(e, 400, Map.of("error", "draft_id_required")); return; }
      String reference = parts[4]; String action = parts.length > 5 ? parts[5] : "";
      Database.Context context = new Database.Context(user(e), workspace(e));
      if ("GET".equals(e.getRequestMethod()) && action.isBlank()) { json(e, 200, agent.draft(reference, context)); return; }
      if ("POST".equals(e.getRequestMethod()) && "confirm".equals(action)) { json(e, 200, agent.confirm(reference, context)); return; }
      if ("POST".equals(e.getRequestMethod()) && "cancel".equals(action)) { json(e, 200, agent.cancel(reference, context)); return; }
      json(e, 405, Map.of("error", "method_not_allowed"));
    } catch (IllegalArgumentException | IllegalStateException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "draft_error", "message", ex.getMessage())); }
  }

  /** 通过 requestId 提供稳定图片地址，避免把 SiliconFlow 的临时签名 URL 直接交给浏览器。 */
  private void aiImage(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      String[] parts = e.getRequestURI().getPath().split("/");
      String requestId = parts.length > 4 ? parts[4] : "";
      if (!requestId.matches("[A-Za-z0-9-]{8,64}")) { error(e, 400, "invalid_image_id", "图片编号无效", false); return; }

      String remoteUrl = null;
      String assetPath = null;
      Database.Context identity = context(e);
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
          "SELECT image_url,asset_path FROM ai_images WHERE request_id=? AND workspace_id=? AND user_id=? AND status='SUCCESS' ORDER BY created_at DESC LIMIT 1")) {
        p.setString(1, requestId);
        p.setBytes(2, Database.uuidBytes(identity.workspaceId()));
        p.setBytes(3, Database.uuidBytes(identity.userId()));
        try (ResultSet rs = p.executeQuery()) {
          if (!rs.next()) { error(e, 404, "image_not_found", "图片不存在", false); return; }
          remoteUrl = rs.getString(1);
          assetPath = rs.getString(2);
        }
      }

      byte[] content = null;
      String contentType = null;
      Path allowedRoot = ImageAssetStore.assetRoot();
      if (assetPath != null && !assetPath.isBlank()) {
        Path file = Path.of(assetPath).toAbsolutePath().normalize();
        if (file.startsWith(allowedRoot) && Files.isRegularFile(file)) {
          content = Files.readAllBytes(file);
          contentType = mimeType(file.getFileName().toString());
        }
      }
      if (content == null && remoteUrl != null && !remoteUrl.isBlank()) {
        URI remote = URI.create(remoteUrl);
        if (!"http".equalsIgnoreCase(remote.getScheme()) && !"https".equalsIgnoreCase(remote.getScheme())) {
          error(e, 404, "image_unavailable", "图片地址无效", false); return;
        }
        HttpResponse<byte[]> response = imageClient.send(HttpRequest.newBuilder(remote)
            .timeout(java.time.Duration.ofSeconds(90)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2 || response.body() == null || response.body().length == 0
            || response.body().length > 20L * 1024 * 1024) {
          error(e, 404, "image_expired", "图片链接已失效，请重新生成", false); return;
        }
        content = response.body();
        contentType = detectImageContentType(content,
            response.headers().firstValue("Content-Type").orElse(mimeType(remote.getPath())));
        Path directory = allowedRoot;
        Files.createDirectories(directory);
        String extension = extensionFromContentType(contentType, remote.getPath());
        Path cached = directory.resolve(requestId + extension).normalize();
        if (cached.startsWith(directory)) {
          Files.write(cached, content);
          try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(
              "UPDATE ai_images SET asset_path=? WHERE request_id=? AND workspace_id=? AND user_id=?")) {
            p.setString(1, cached.toAbsolutePath().toString()); p.setString(2, requestId);
            p.setBytes(3, Database.uuidBytes(identity.workspaceId())); p.setBytes(4, Database.uuidBytes(identity.userId()));
            p.executeUpdate();
          }
        }
      }
      if (content == null) { error(e, 404, "image_unavailable", "图片暂时无法读取，请重新生成", true); return; }
      cors(e);
      e.getResponseHeaders().set("Content-Type", contentType == null ? "application/octet-stream" : contentType);
      e.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
      e.sendResponseHeaders(200, content.length);
      try (OutputStream out = e.getResponseBody()) { out.write(content); }
    } catch (IllegalArgumentException ex) {
      error(e, 400, "invalid_image_request", ex.getMessage(), false);
    } catch (SQLException ex) {
      LOG.error("[AI图片读取] 数据库异常", ex);
      error(e, 500, "database_error", "图片读取失败，请稍后重试", true);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      error(e, 503, "image_fetch_interrupted", "图片读取被中断", true);
    }
  }

  private String extensionFromContentType(String contentType, String path) {
    String type = contentType == null ? "" : contentType.toLowerCase();
    if (type.contains("jpeg") || type.contains("jpg")) return ".jpg";
    if (type.contains("webp")) return ".webp";
    if (type.contains("gif")) return ".gif";
    if (type.contains("svg")) return ".svg";
    if (type.contains("png")) return ".png";
    String lower = path == null ? "" : path.toLowerCase();
    for (String suffix : new String[]{".jpg", ".jpeg", ".webp", ".gif", ".svg", ".png"}) if (lower.endsWith(suffix)) return suffix;
    return ".png";
  }

  private String detectImageContentType(byte[] content, String fallback) {
    if (content != null && content.length >= 8
        && (content[0] & 0xff) == 0x89 && content[1] == 0x50 && content[2] == 0x4e && content[3] == 0x47) return "image/png";
    if (content != null && content.length >= 3
        && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8 && (content[2] & 0xff) == 0xff) return "image/jpeg";
    if (content != null && content.length >= 12
        && content[0] == 'R' && content[1] == 'I' && content[2] == 'F' && content[3] == 'F'
        && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P') return "image/webp";
    return fallback != null && fallback.toLowerCase().startsWith("image/") ? fallback : "image/png";
  }

  private String mimeType(String name) {
    String lower = name == null ? "" : name.toLowerCase();
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
    if (lower.endsWith(".webp")) return "image/webp";
    if (lower.endsWith(".gif")) return "image/gif";
    if (lower.endsWith(".svg")) return "image/svg+xml";
    if (lower.endsWith(".png")) return "image/png";
    return "application/octet-stream";
  }

  private void aiSession(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      json(e, 200, agent.session(context(e), "web"));
    } catch (Exception ex) { ex.printStackTrace(); error(e, 500, "session_error", ex.getMessage(), true); }
  }

  private void aiChangeSet(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 6 || parts[4].isBlank() || !"undo".equals(parts[5])) { error(e, 400, "change_set_required", "缺少要撤销的变更集", false); return; }
      if (!"POST".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      json(e, 200, agent.undo(parts[4], context(e)));
    } catch (IllegalArgumentException | IllegalStateException ex) { error(e, 409, "undo_rejected", ex.getMessage(), false); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "undo_error", ex.getMessage(), true); }
  }

  private void reviewFacts(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, agent.reviewFacts(new Database.Context(user(e), workspace(e))));
    } catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void aiConversations(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if ("GET".equals(e.getRequestMethod())) {
        json(e, 200, agent.conversations(context(e), "web")); return;
      }
      if ("POST".equals(e.getRequestMethod())) {
        json(e, 201, agent.createConversation(context(e), "web")); return;
      }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (Exception ex) {
      LOG.error("[AI会话列表失败] 原因={}", ex.getMessage(), ex);
      error(e, 500, "conversation_error", ex.getMessage(), true);
    }
  }

  private void aiConversation(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 5 || parts[4].isBlank()) {
        error(e, 400, "conversation_id_required", "缺少会话编号", false); return;
      }
      String id = parts[4];
      if ("GET".equals(e.getRequestMethod())) {
        json(e, 200, agent.conversation(id, context(e), "web")); return;
      }
      if ("PATCH".equals(e.getRequestMethod()) || "PUT".equals(e.getRequestMethod())) {
        json(e, 200, agent.renameConversation(id, body(e), context(e))); return;
      }
      if ("DELETE".equals(e.getRequestMethod())) {
        agent.deleteConversation(id, context(e)); json(e, 204, Map.of()); return;
      }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) {
      error(e, 404, "conversation_not_found", ex.getMessage(), false);
    } catch (Exception ex) {
      LOG.error("[AI会话操作失败] 原因={}", ex.getMessage(), ex);
      error(e, 500, "conversation_error", ex.getMessage(), true);
    }
  }

  private void aiMemories(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) {
        error(e, 405, "method_not_allowed", "请求方法不支持", false); return;
      }
      json(e, 200, agent.memories(context(e)));
    } catch (Exception ex) {
      LOG.error("[AI记忆读取失败] 原因={}", ex.getMessage(), ex);
      error(e, 500, "memory_error", ex.getMessage(), true);
    }
  }

  private void aiMemory(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 5 || parts[4].isBlank()) {
        error(e, 400, "memory_id_required", "缺少记忆编号", false); return;
      }
      String id = parts[4];
      if ("PATCH".equals(e.getRequestMethod()) || "PUT".equals(e.getRequestMethod())) {
        json(e, 200, agent.updateMemory(id, body(e), context(e))); return;
      }
      if ("DELETE".equals(e.getRequestMethod())) {
        agent.deleteMemory(id, context(e)); json(e, 204, Map.of()); return;
      }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) {
      error(e, 404, "memory_not_found", ex.getMessage(), false);
    } catch (Exception ex) {
      LOG.error("[AI记忆操作失败] 原因={}", ex.getMessage(), ex);
      error(e, 500, "memory_error", ex.getMessage(), true);
    }
  }

  private void agentRuns(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      json(e, 202, agent.startAsync(body(e), context(e), "web"));
    } catch (IllegalArgumentException ex) { error(e, 400, "invalid_agent_request", ex.getMessage(), false); }
      catch (IllegalStateException ex) { error(e, 503, "agent_unavailable", ex.getMessage(), true); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "agent_error", ex.getMessage(), true); }
  }

  private void agentFiles(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] path = e.getRequestURI().getPath().split("/");
      if ("DELETE".equals(e.getRequestMethod()) && path.length == 5 && !path[4].isBlank()) {
        agent.deleteDocument(path[4], context(e));
        json(e, 204, Map.of());
        return;
      }
      if (!"POST".equals(e.getRequestMethod())) {
        error(e, 405, "method_not_allowed", "请求方法不支持", false); return;
      }
      String encodedName = e.getRequestHeaders().getFirst("X-File-Name");
      if (encodedName == null || encodedName.isBlank()) {
        error(e, 400, "file_name_required", "缺少文件名", false); return;
      }
      long contentLength = Long.parseLong(e.getRequestHeaders().getFirst("Content-Length") == null
          ? "-1" : e.getRequestHeaders().getFirst("Content-Length"));
      if (contentLength > MAX_AGENT_FILE_BYTES) {
        error(e, 413, "file_too_large", "文件不能超过 25 MB", false); return;
      }
      byte[] bytes;
      try (InputStream input = e.getRequestBody()) { bytes = input.readNBytes(MAX_AGENT_FILE_BYTES + 1); }
      if (bytes.length == 0) { error(e, 400, "file_empty", "文件内容为空", false); return; }
      if (bytes.length > MAX_AGENT_FILE_BYTES) {
        error(e, 413, "file_too_large", "文件不能超过 25 MB", false); return;
      }
      String fileName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8).replace('\\', '/');
      fileName = fileName.substring(fileName.lastIndexOf('/') + 1).trim();
      if (fileName.isBlank()) { error(e, 400, "file_name_required", "文件名无效", false); return; }
      String mediaType = e.getRequestHeaders().getFirst("Content-Type");
      DocumentResult result = agent.uploadDocument(bytes, fileName,
          mediaType == null ? "application/octet-stream" : mediaType, context(e));
      json(e, 201, result.toJson());
    } catch (NumberFormatException ex) {
      error(e, 400, "invalid_content_length", "文件长度无效", false);
    } catch (IllegalArgumentException ex) {
      error(e, 400, "invalid_document", ex.getMessage(), false);
    } catch (IllegalStateException ex) {
      error(e, 503, "document_service_unavailable", ex.getMessage(), true);
    } catch (Exception ex) {
      LOG.error("[文件上传失败] 原因={}", ex.getMessage(), ex);
      error(e, 500, "document_upload_error", "文件解析失败：" + ex.getMessage(), true);
    }
  }

  private void agentRun(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 5 || parts[4].isBlank()) { error(e, 400, "run_id_required", "缺少 Agent 运行编号", false); return; }
      String runId = parts[4];
      String action = parts.length > 5 ? parts[5] : "";
      if ("GET".equals(e.getRequestMethod()) && action.isBlank()) { json(e, 200, agent.get(runId, context(e))); return; }
      if ("POST".equals(e.getRequestMethod()) && "resume".equals(action)) { json(e, 202, agent.resumeAsync(runId, body(e), context(e))); return; }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) { error(e, 404, "agent_run_not_found", ex.getMessage(), false); }
      catch (IllegalStateException ex) { error(e, 409, "agent_run_rejected", ex.getMessage(), false); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "agent_run_error", ex.getMessage(), true); }
  }

  private void agentDraft(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 6 || parts[4].isBlank()) { error(e, 400, "draft_id_required", "缺少草案编号", false); return; }
      String draftId = parts[4];
      String action = parts[5];
      if ("POST".equals(e.getRequestMethod()) && "confirm".equals(action)) { json(e, 200, agent.confirm(draftId, context(e))); return; }
      if ("POST".equals(e.getRequestMethod()) && "cancel".equals(action)) { json(e, 200, agent.cancel(draftId, context(e))); return; }
      if ("POST".equals(e.getRequestMethod()) && "modify".equals(action)) { json(e, 200, agent.modifyDraft(draftId, body(e), context(e))); return; }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException | IllegalStateException ex) { error(e, 409, "draft_rejected", ex.getMessage(), false); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "draft_error", ex.getMessage(), true); }
  }

  private void reviewToday(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      boolean regenerate = "POST".equals(e.getRequestMethod())
          && e.getRequestURI().getPath().endsWith("/regenerate");
      if (!"GET".equals(e.getRequestMethod()) && !regenerate) {
        error(e, 405, "method_not_allowed", "请求方法不支持", false); return;
      }
      json(e, 200, agent.reviewToday(context(e), regenerate));
    } catch (IllegalStateException ex) { error(e, 503, "review_unavailable", ex.getMessage(), true); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "review_error", ex.getMessage(), true); }
  }

  private void learningGoals(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      JsonArray rows = new JsonArray();
      for (var goal : learningService.listGoals(context(e))) rows.add(goal.toJson());
      JsonObject result = new JsonObject();
      result.add("goals", rows);
      json(e, 200, result);
    } catch (SQLException ex) { error(e, 500, "learning_goals_error", ex.getMessage(), true); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "learning_goals_error", ex.getMessage(), true); }
  }

  private void learningGoal(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 5 || parts[4].isBlank()) { error(e, 400, "goal_id_required", "缺少学习目标编号", false); return; }
      Database.Context context = context(e);
      var goal = learningService.getGoal(parts[4], context);
      if (goal == null) { error(e, 404, "learning_goal_not_found", "学习目标不存在", false); return; }
      JsonObject result = new JsonObject();
      result.add("goal", goal.toJson());
      JsonArray days = new JsonArray();
      if (goal.planId() != null && !goal.planId().isBlank()) {
        for (var task : learningService.planTasks(goal.planId(), context)) {
          String date = task.dueAt() == null ? "未排期" : task.dueAt().toLocalDate().toString();
          JsonObject day = findDay(days, date);
          if (day == null) {
            day = new JsonObject(); day.addProperty("date", date);
            day.add("tasks", new JsonArray()); days.add(day);
          }
          day.getAsJsonArray("tasks").add(task.toJson());
        }
      }
      result.add("days", days);
      JsonArray sessions = new JsonArray();
      for (var session : learningService.listSessionsByGoal(parts[4], context)) sessions.add(session.toJson());
      result.add("sessions", sessions);
      json(e, 200, result);
    } catch (SQLException ex) { error(e, 500, "learning_goal_error", ex.getMessage(), true); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "learning_goal_error", ex.getMessage(), true); }
  }

  private JsonObject findDay(JsonArray days, String date) {
    for (JsonElement element : days) {
      JsonObject day = element.getAsJsonObject();
      if (date.equals(day.get("date").getAsString())) return day;
    }
    return null;
  }

  private void wechatAiCommand(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      Database.Context context = database.contextForExternalUser(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      json(e, 200, agent.start(body(e), context, "wechat"));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (IllegalStateException ex) { json(e, 503, Map.of("error", "ai_unavailable", "message", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "ai_command_error", "message", ex.getMessage())); }
  }

  private void wechatAiDraft(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if (parts.length < 7 || parts[5].isBlank()) { json(e, 400, Map.of("error", "draft_id_required")); return; }
      Database.Context context = database.contextForExternalUser(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      String reference = parts[5]; String action = parts[6];
      if ("POST".equals(e.getRequestMethod()) && "confirm".equals(action)) { json(e, 200, agent.confirm(reference, context)); return; }
      if ("POST".equals(e.getRequestMethod()) && "cancel".equals(action)) { json(e, 200, agent.cancel(reference, context)); return; }
      json(e, 405, Map.of("error", "method_not_allowed"));
    } catch (IllegalArgumentException | IllegalStateException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "draft_error", "message", ex.getMessage())); }
  }

  private void wechatCapture(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      JsonObject body = body(e);
      String text = string(body, "text", "").trim();
      String externalId = e.getRequestHeaders().getFirst("X-Wechat-User-Id");
      if (text.isBlank()) { json(e, 400, Map.of("error", "text_required")); return; }
      String type = null, title = text;
      String[] prefixes = {"计划:", "计划：", "待办:", "待办：", "日程:", "日程：", "笔记:", "笔记："};
      for (String prefix : prefixes) if (text.startsWith(prefix)) { type = prefix.substring(0, 2); title = text.substring(prefix.length()).trim(); break; }
      if (type == null) { json(e, 200, Map.of("handled", false)); return; }
      if (title.isBlank()) { json(e, 400, Map.of("error", "title_required")); return; }
      Database.Context context = database.contextForExternalUser(externalId);
      if (!type.equals("笔记")) {
        JsonObject input = new JsonObject(); input.addProperty("message", text);
        JsonObject result = agent.start(input, context, "wechat");
        result.addProperty("handled", true); result.addProperty("message", result.get("reply").getAsString());
        json(e, 200, result); return;
      }
      UUID id = UUID.randomUUID();
      insertNote(context, id, title);
      json(e, 201, Map.of("handled", true, "type", type, "id", id.toString(), "title", title, "message", "已记录到长路计划"));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
      catch (Exception ex) { ex.printStackTrace(); error(e, 500, "ai_command_error", ex.getMessage(), true); }
  }

  private void wechatCommand(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      String text = string(body(e), "text", "").trim();
      if (text.isBlank()) { json(e, 400, Map.of("error", "text_required")); return; }
      Database.Context context = database.contextForExternalUser(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      String normalized = text.replaceAll("[\\s，。！？、,.!?]", "");
      JsonObject result = new JsonObject(); result.addProperty("handled", true);
      if (normalized.equals("今天还有什么") || normalized.equals("今天还有哪些")) {
        result.addProperty("message", todayOpenItems(context));
      } else if (normalized.equals("计划完成得怎么样")) {
        result.addProperty("message", progressSummary(context));
      } else {
        result.addProperty("handled", false);
      }
      json(e, 200, result);
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private String todayOpenItems(Database.Context context) throws SQLException {
    List<String> schedules = new ArrayList<>();
    List<String> todos = new ArrayList<>();
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT title FROM schedule_items WHERE workspace_id = ? AND DATE(start_at) = CURDATE() AND status <> 'done' ORDER BY start_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add("日程：" + rs.getString(1)); }
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT title FROM todos WHERE workspace_id = ? AND (due_at IS NULL OR DATE(due_at) = CURDATE()) AND status <> 'done' ORDER BY due_at LIMIT 10")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add("待办：" + rs.getString(1)); }
    }
    List<String> all = new ArrayList<>(); all.addAll(schedules); all.addAll(todos);
    if (all.isEmpty()) return "今天没有未完成的日程或待办，按自己的节奏休息一下。";
    return "今天还有：\n- " + String.join("\n- ", all);
  }

  private String progressSummary(Database.Context context) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT COUNT(*), COALESCE(AVG(progress), 0) FROM plans WHERE workspace_id = ? AND status = 'active'")) {
      p.setBytes(1, Database.uuidBytes(context.workspaceId())); try (ResultSet rs = p.executeQuery()) {
        if (!rs.next()) return "暂时还没有长期计划。";
        return "当前有 " + rs.getInt(1) + " 个进行中的长期计划，平均完成度 " + Math.round(rs.getDouble(2)) + "%。";
      }
    }
  }

  private String completeByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title);
    if (item == null) return "没有找到名称包含“" + title + "”的计划、日程或待办。";
    String status = item.table.equals("plans") ? "completed" : "done";
    String sql = item.table.equals("todos")
        ? "UPDATE todos SET status = ? WHERE id = ? AND workspace_id = ?"
        : "UPDATE " + item.table + " SET status = ?, progress = 100 WHERE id = ? AND workspace_id = ?";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setString(1, status); p.setBytes(2, Database.uuidBytes(item.id)); p.setBytes(3, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
    }
    return "已完成：" + item.title;
  }

  private String deleteByTitle(Database.Context context, String title) throws SQLException {
    ItemRef item = findByTitle(context, title);
    if (item == null) return "没有找到名称包含“" + title + "”的记录。";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM " + item.table + " WHERE id = ? AND workspace_id = ?")) {
      p.setBytes(1, Database.uuidBytes(item.id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.executeUpdate();
    }
    return "已删除：" + item.title;
  }

  private ItemRef findByTitle(Database.Context context, String title) throws SQLException {
    String[] tables = {"todos", "schedule_items", "plans"};
    for (String table : tables) {
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT id, title FROM " + table + " WHERE workspace_id = ? AND title LIKE ? ORDER BY updated_at DESC LIMIT 1")) {
        p.setBytes(1, Database.uuidBytes(context.workspaceId())); p.setString(2, "%" + title + "%");
        try (ResultSet rs = p.executeQuery()) { if (rs.next()) return new ItemRef(table, Database.bytesUuid(rs.getBytes("id")), rs.getString("title")); }
      }
    }
    return null;
  }

  private record ItemRef(String table, UUID id, String title) {}

  private void insertPlan(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO plans (id, workspace_id, owner_id, title, description, color) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setString(5, "微信快速记录"); p.setString(6, "#D39A24"); p.executeUpdate();
    }
  }

  private void insertTodo(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO todos (id, workspace_id, created_by, title, due_at) VALUES (?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now())); p.executeUpdate();
    }
  }

  private void insertSchedule(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO schedule_items (id, workspace_id, created_by, title, start_at) VALUES (?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setTimestamp(5, Timestamp.valueOf(LocalDateTime.now())); p.executeUpdate();
    }
  }

  private void insertNote(Database.Context context, UUID id, String title) throws SQLException {
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO notes (id, workspace_id, created_by, title, excerpt, content) VALUES (?, ?, ?, ?, ?, ?)")) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(context.workspaceId())); p.setBytes(3, Database.uuidBytes(context.userId())); p.setString(4, title); p.setString(5, "微信快速记录"); p.setString(6, title); p.executeUpdate();
    }
  }

  private void wechatBriefing(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      BriefingResult briefing = agent.briefing(e.getRequestHeaders().getFirst("X-Wechat-User-Id"));
      json(e, 200, Map.of("plans", briefing.plans(), "progress", briefing.progress(), "pendingTodos", briefing.pendingTodos(), "overdueTodos", briefing.overdueTodos(), "tone", briefing.tone(), "message", briefing.message()));
    } catch (Exception ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "briefing_error", "message", ex.getMessage())); }
  }

  private void tasks(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      String id = parts.length > 3 && !parts[3].isBlank() ? parts[3] : null;
      String action = parts.length > 4 ? parts[4] : "";
      Database.Context context = context(e);
      if ("POST".equals(e.getRequestMethod()) && id == null) {
        JsonObject b = body(e); UUID planId = UUID.fromString(string(b, "planId", ""));
        json(e, 201, planExecution.createTask(context, planId, b, "web")); return;
      }
      requireId(id);
      if (("PUT".equals(e.getRequestMethod()) || "PATCH".equals(e.getRequestMethod())) && action.isBlank()) {
        json(e, 200, planExecution.updateTask(context, UUID.fromString(id), body(e), "web")); return;
      }
      if ("DELETE".equals(e.getRequestMethod()) && action.isBlank()) {
        JsonObject b = body(e); planExecution.softDeleteTask(context, UUID.fromString(id), (int) number(b, "expectedVersion", 0), "web");
        json(e, 204, Map.of("deleted", true)); return;
      }
      if ("POST".equals(e.getRequestMethod()) && "restore".equals(action)) {
        json(e, 200, planExecution.restoreTask(context, UUID.fromString(id), "web")); return;
      }
      if ("POST".equals(e.getRequestMethod()) && List.of("complete", "delay", "block", "skip", "cancel", "reopen").contains(action)) {
        JsonObject b = body(e); String status = switch (action) { case "complete" -> "done"; case "delay", "reopen" -> "pending"; case "block" -> "blocked"; case "skip" -> "skipped"; default -> "cancelled"; };
        b.addProperty("status", status); b.addProperty("actionType", action + "_task");
        json(e, 200, planExecution.updateTask(context, UUID.fromString(id), b, "web")); return;
      }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) { error(e, 400, "invalid_task", ex.getMessage(), false); }
      catch (IllegalStateException ex) { error(e, 409, "version_conflict", ex.getMessage(), false); }
      catch (SQLException ex) { LOG.error("[任务保存失败] 数据库异常", ex); error(e, 500, "database_error", "任务保存失败，请稍后重试", true); }
      catch (Exception ex) { LOG.error("[任务保存失败] 未处理异常", ex); error(e, 500, "task_error", "任务保存失败，请稍后重试", true); }
  }

  private void planningPreferences(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if ("GET".equals(e.getRequestMethod())) { json(e, 200, planExecution.preference(context(e))); return; }
      if ("PUT".equals(e.getRequestMethod()) || "POST".equals(e.getRequestMethod())) { json(e, 200, planExecution.savePreference(context(e), body(e))); return; }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) { error(e, 400, "invalid_preference", ex.getMessage(), false); }
      catch (SQLException ex) { ex.printStackTrace(); error(e, 500, "database_error", ex.getMessage(), true); }
  }

  private void trash(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      if ("GET".equals(e.getRequestMethod()) && parts.length == 3) { json(e, 200, planExecution.listTrash(context(e))); return; }
      if ("DELETE".equals(e.getRequestMethod()) && parts.length == 5) {
        String type = parts[3]; UUID id = UUID.fromString(parts[4]);
        if (purgeTrashItem(type, id, workspace(e)) == 0) throw new IllegalArgumentException("记录不在回收站");
        json(e, 204, Map.of()); return;
      }
      if ("POST".equals(e.getRequestMethod()) && parts.length >= 6 && "restore".equals(parts[5])) {
        String type = parts[3]; UUID id = UUID.fromString(parts[4]);
        if ("task".equals(type)) { json(e, 200, planExecution.restoreTask(context(e), id, "web")); return; }
        if ("stage".equals(type)) { json(e, 200, planExecution.restoreStage(context(e), id, "web")); return; }
        String table = switch (type) { case "plan" -> "plans"; case "todo" -> "todos"; case "schedule" -> "schedule_items"; default -> throw new IllegalArgumentException("不支持的回收站类型"); };
        try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("UPDATE " + table + " SET deleted_at=NULL,purge_after=NULL,version=version+1 WHERE id=? AND workspace_id=? AND deleted_at IS NOT NULL")) {
          p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(workspace(e)));
          if (p.executeUpdate() == 0) throw new IllegalArgumentException("记录不在回收站");
        }
        json(e, 200, Map.of("id", id.toString(), "restored", true)); return;
      }
      error(e, 405, "method_not_allowed", "请求方法不支持", false);
    } catch (IllegalArgumentException ex) { error(e, 400, "invalid_trash_operation", ex.getMessage(), false); }
      catch (SQLException ex) { ex.printStackTrace(); error(e, 500, "database_error", ex.getMessage(), true); }
  }

  /** 永久删除严格限定为当前工作区内已经软删除的记录。 */
  private int purgeTrashItem(String type, UUID id, UUID workspaceId) throws SQLException {
    String sql = switch (type) {
      case "plan" -> "DELETE FROM plans WHERE id=? AND workspace_id=? AND deleted_at IS NOT NULL";
      case "todo" -> "DELETE FROM todos WHERE id=? AND workspace_id=? AND deleted_at IS NOT NULL";
      case "schedule" -> "DELETE FROM schedule_items WHERE id=? AND workspace_id=? AND deleted_at IS NOT NULL";
      case "stage" -> "DELETE s FROM plan_stages s JOIN plans p ON p.id=s.plan_id WHERE s.id=? AND p.workspace_id=? AND s.deleted_at IS NOT NULL";
      case "task" -> "DELETE t FROM plan_tasks t JOIN plans p ON p.id=t.plan_id WHERE t.id=? AND p.workspace_id=? AND t.deleted_at IS NOT NULL";
      default -> throw new IllegalArgumentException("不支持的回收站类型");
    };
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(id)); p.setBytes(2, Database.uuidBytes(workspaceId)); return p.executeUpdate();
    }
  }

  private void planSubresource(HttpExchange e) throws IOException {
    String[] parts = e.getRequestURI().getPath().split("/");
    if (parts.length < 5) { crud(e, "plans"); return; }
    try {
      if (options(e)) return;
      String planId = parts[3]; requireId(planId);
      if ("tasks".equals(parts[4])) {
        if (!"GET".equals(e.getRequestMethod())) { error(e, 405, "method_not_allowed", "请求方法不支持", false); return; }
        json(e, 200, planExecution.listTasks(UUID.fromString(planId), context(e))); return;
      }
      if (!"stages".equals(parts[4])) { error(e, 404, "not_found", "接口不存在", false); return; }
      String stageId = parts.length > 5 && !parts[5].isBlank() ? parts[5] : null;
      switch (e.getRequestMethod()) {
        case "GET" -> listPlanStages(e, planId);
        case "POST" -> createPlanStage(e, planId);
        case "PUT", "PATCH" -> { requireId(stageId); updatePlanStage(e, planId, stageId); }
        case "DELETE" -> { requireId(stageId); deletePlanStage(e, planId, stageId); }
        default -> json(e, 405, Map.of("error", "method_not_allowed"));
      }
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void listPlanStages(HttpExchange e, String planId) throws SQLException, IOException {
    String sql = "SELECT s.* FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.plan_id = ? AND p.workspace_id = ? AND s.deleted_at IS NULL AND p.deleted_at IS NULL ORDER BY s.sort_order, s.created_at";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(planId))); p.setBytes(2, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) rows.add(stageRow(rs)); json(e, 200, rows); }
    }
  }

  private void createPlanStage(HttpExchange e, String planId) throws SQLException, IOException {
    JsonObject b = body(e); if (!b.has("dueDate") && b.has("dueLabel")) b.add("dueDate", b.get("dueLabel"));
    json(e, 201, planExecution.createStage(context(e), UUID.fromString(planId), b, "web"));
  }

  private void updatePlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    JsonObject b = body(e); if (!b.has("dueDate") && b.has("dueLabel")) b.add("dueDate", b.get("dueLabel"));
    json(e, 200, planExecution.updateStage(context(e), UUID.fromString(stageId), b, "web"));
  }

  private void deletePlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    JsonObject b = body(e); int expectedVersion = (int) number(b, "expectedVersion", 0);
    planExecution.softDeleteStage(context(e), UUID.fromString(stageId), expectedVersion, "web"); json(e, 204, Map.of("deleted", true));
  }

  private void getPlanStage(HttpExchange e, String planId, String stageId) throws SQLException, IOException {
    String sql = "SELECT s.* FROM plan_stages s JOIN plans p ON p.id = s.plan_id WHERE s.id = ? AND s.plan_id = ? AND p.workspace_id = ? AND s.deleted_at IS NULL AND p.deleted_at IS NULL";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(stageId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(planId))); p.setBytes(3, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) { json(e, 404, Map.of("error", "stage_not_found")); return; } json(e, 200, stageRow(rs)); }
    }
  }

  private JsonObject stageRow(ResultSet rs) throws SQLException {
    JsonObject o = new JsonObject(); o.addProperty("id", Database.id(rs, "id")); o.addProperty("title", rs.getString("title"));
    o.addProperty("progress", rs.getDouble("progress")); o.addProperty("status", rs.getString("status"));
    o.addProperty("taskProgress", rs.getDouble("task_progress")); o.addProperty("effortProgress", rs.getDouble("effort_progress")); o.addProperty("version", rs.getInt("version"));
    o.addProperty("dueLabel", rs.getDate("due_date") == null ? "待安排" : rs.getDate("due_date").toString());
    return o;
  }

  private void updatePlanProgress(Connection c, UUID planId) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("UPDATE plans SET progress = (SELECT COALESCE(AVG(progress), 0) FROM plan_stages WHERE plan_id = ?) WHERE id = ?")) {
      p.setBytes(1, Database.uuidBytes(planId)); p.setBytes(2, Database.uuidBytes(planId)); p.executeUpdate();
    }
  }

  private java.sql.Date safeDate(String value) {
    if (value == null || value.isBlank()) return null;
    try { return java.sql.Date.valueOf(value); } catch (IllegalArgumentException ignored) { return null; }
  }

  private void stats(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      json(e, 200, loadStats(workspace(e)));
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private JsonObject loadStats(UUID workspace) throws SQLException {
      LocalDate today = LocalDate.now();
      LocalDate from = YearMonth.from(today).minusMonths(5).atDay(1);
      LocalDate to = YearMonth.from(today).atEndOfMonth();
      Map<LocalDate, DailyStat> values = new LinkedHashMap<>();
      for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) values.put(date, new DailyStat());
      // 日程只是任务的时间块，完成率只由计划任务和独立待办决定。
      String taskSql = "SELECT DATE(COALESCE(t.due_at,t.created_at)) day, COUNT(*) planned, "
          + "SUM(t.status='done') completed, "
          + "SUM(CASE WHEN t.status='done' THEN COALESCE(t.actual_minutes,0) ELSE 0 END) minutes "
          + "FROM plan_tasks t JOIN plans p ON p.id=t.plan_id JOIN plan_stages s ON s.id=t.stage_id "
          + "WHERE p.workspace_id=? AND p.deleted_at IS NULL AND s.deleted_at IS NULL AND t.deleted_at IS NULL "
          + "AND t.status NOT IN ('cancelled','skipped') "
          + "AND NOT EXISTS (SELECT 1 FROM schedule_items recurrence_item WHERE recurrence_item.task_id=t.id "
          + "AND recurrence_item.deleted_at IS NULL AND recurrence_item.status<>'cancelled') "
          + "AND DATE(COALESCE(t.due_at,t.created_at)) BETWEEN ? AND ? "
          + "GROUP BY DATE(COALESCE(t.due_at,t.created_at))";
      String recurrenceSql = "SELECT DATE(i.start_at) day,COUNT(*) planned,"
          + "SUM(CASE WHEN t.status='done' OR i.status='done' THEN 1 ELSE 0 END) completed "
          + "FROM schedule_items i JOIN plan_tasks t ON t.id=i.task_id JOIN plans p ON p.id=t.plan_id "
          + "JOIN plan_stages s ON s.id=t.stage_id WHERE p.workspace_id=? AND p.deleted_at IS NULL "
          + "AND s.deleted_at IS NULL AND t.deleted_at IS NULL AND t.status NOT IN ('cancelled','skipped') "
          + "AND i.deleted_at IS NULL AND i.status<>'cancelled' "
          + "AND DATE(i.start_at) BETWEEN ? AND ? GROUP BY DATE(i.start_at)";
      String todoSql = "SELECT DATE(COALESCE(due_at,created_at)) day, COUNT(*) planned, SUM(status='done') completed "
          + "FROM todos WHERE workspace_id=? AND deleted_at IS NULL AND status<>'cancelled' "
          + "AND DATE(COALESCE(due_at,created_at)) BETWEEN ? AND ? GROUP BY DATE(COALESCE(due_at,created_at))";
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement(taskSql)) { p.setBytes(1, Database.uuidBytes(workspace)); p.setDate(2, java.sql.Date.valueOf(from)); p.setDate(3, java.sql.Date.valueOf(to)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) values.get(rs.getDate("day").toLocalDate()).add(rs.getInt("planned"), rs.getInt("completed"), rs.getInt("minutes")); } }
        try (PreparedStatement p = c.prepareStatement(recurrenceSql)) { p.setBytes(1, Database.uuidBytes(workspace)); p.setDate(2, java.sql.Date.valueOf(from)); p.setDate(3, java.sql.Date.valueOf(to)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) values.get(rs.getDate("day").toLocalDate()).add(rs.getInt("planned"), rs.getInt("completed"), 0); } }
        try (PreparedStatement p = c.prepareStatement(todoSql)) { p.setBytes(1, Database.uuidBytes(workspace)); p.setDate(2, java.sql.Date.valueOf(from)); p.setDate(3, java.sql.Date.valueOf(to)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) values.get(rs.getDate("day").toLocalDate()).add(rs.getInt("planned"), rs.getInt("completed"), 0); } }
      }
      return statsPayload(values, today);
  }

  private JsonObject statsPayload(Map<LocalDate, DailyStat> values, LocalDate today) {
    JsonObject result = new JsonObject(); com.google.gson.JsonArray daily = new com.google.gson.JsonArray(); com.google.gson.JsonArray heatmap = new com.google.gson.JsonArray(); com.google.gson.JsonArray monthly = new com.google.gson.JsonArray();
    YearMonth current = YearMonth.from(today); int monthPlanned = 0, monthCompleted = 0, monthMinutes = 0;
    for (int day = 1; day <= current.lengthOfMonth(); day++) {
      LocalDate date = current.atDay(day); DailyStat stat = values.getOrDefault(date, new DailyStat());
      JsonObject row = new JsonObject(); row.addProperty("day", String.valueOf(day)); row.addProperty("planned", stat.planned); row.addProperty("completed", stat.completed); row.addProperty("minutes", stat.minutes); daily.add(row);
      monthPlanned += stat.planned; monthCompleted += stat.completed; monthMinutes += stat.minutes;
    }
    for (Map.Entry<LocalDate, DailyStat> entry : values.entrySet()) { JsonObject row = new JsonObject(); DailyStat stat = entry.getValue(); row.addProperty("id", entry.getKey().toString()); row.addProperty("value", Math.min(5, stat.completed)); row.addProperty("planned", stat.planned); row.addProperty("completed", stat.completed); row.addProperty("pending", Math.max(0, stat.planned - stat.completed)); row.addProperty("label", entry.getKey().toString()); heatmap.add(row); }
    for (int offset = 5; offset >= 0; offset--) { YearMonth month = current.minusMonths(offset); int planned = 0, completed = 0; for (Map.Entry<LocalDate, DailyStat> entry : values.entrySet()) if (YearMonth.from(entry.getKey()).equals(month)) { planned += entry.getValue().planned; completed += entry.getValue().completed; } JsonObject row = new JsonObject(); row.addProperty("month", month.getMonthValue() + " 月"); row.addProperty("completion", planned == 0 ? 0 : Math.round(completed * 100.0 / planned)); row.addProperty("completed", completed); row.addProperty("delayed", Math.max(0, planned - completed)); monthly.add(row); }
    int streak = 0; for (LocalDate date = today; values.containsKey(date) && values.get(date).completed > 0; date = date.minusDays(1)) streak++;
    JsonObject metrics = new JsonObject(); metrics.addProperty("completion", monthPlanned == 0 ? 0 : Math.round(monthCompleted * 100.0 / monthPlanned)); metrics.addProperty("completed", monthCompleted); metrics.addProperty("planned", monthPlanned); metrics.addProperty("focusHours", Math.round(monthMinutes / 6.0) / 10.0); metrics.addProperty("streak", streak);
    result.add("daily", daily); result.add("heatmap", heatmap); result.add("monthly", monthly); result.add("metrics", metrics); return result;
  }

  private static final class DailyStat { int planned; int completed; int minutes; void add(int p, int c, int m) { planned += p; completed += c; minutes += m; } }

  private void excelExport(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      UUID workspace = workspace(e); List<List<String>> plans = new ArrayList<>(), schedules = new ArrayList<>(), todos = new ArrayList<>();
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement("SELECT title, description, progress, status, due_date FROM plans WHERE workspace_id = ? ORDER BY updated_at DESC")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) plans.add(List.of(rs.getString(1), String.valueOf(rs.getString(2)), rs.getString(3) + "%", rs.getString(4), String.valueOf(rs.getDate(5)))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(start_at), TIME_FORMAT(start_at, '%H:%i'), title, duration_minutes, status FROM schedule_items WHERE workspace_id = ? ORDER BY start_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add(List.of(String.valueOf(rs.getDate(1)), rs.getString(2), rs.getString(3), rs.getString(4) + " 分钟", rs.getString(5))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(due_at), TIME_FORMAT(due_at, '%H:%i'), title, priority, status FROM todos WHERE workspace_id = ? ORDER BY due_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add(List.of(String.valueOf(rs.getDate(1)), String.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5))); } }
      }
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(); try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
        zipText(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override PartName=\"/xl/worksheets/sheet3.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/></Types>");
        zipText(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        zipText(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet name=\"长期计划\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"日程安排\" sheetId=\"2\" r:id=\"rId2\"/><sheet name=\"一次性待办\" sheetId=\"3\" r:id=\"rId3\"/></sheets></workbook>");
        zipText(zip, "xl/_rels/workbook.xml.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/><Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/><Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet3.xml\"/></Relationships>");
        zipText(zip, "xl/worksheets/sheet1.xml", sheetXml(new String[]{"计划", "说明", "进度", "状态", "截止日期"}, plans));
        zipText(zip, "xl/worksheets/sheet2.xml", sheetXml(new String[]{"日期", "时间", "日程", "时长", "状态"}, schedules));
        zipText(zip, "xl/worksheets/sheet3.xml", sheetXml(new String[]{"日期", "时间", "待办", "优先级", "状态"}, todos));
      }
      e.getResponseHeaders().set("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); e.getResponseHeaders().set("Content-Disposition", "attachment; filename=changlu-plan.xlsx"); e.setAttribute(RESPONSE_STATUS_ATTRIBUTE, 200); e.sendResponseHeaders(200, bytes.size()); try (OutputStream out = e.getResponseBody()) { bytes.writeTo(out); }
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void pdfExport(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"GET".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      UUID workspace = workspace(e);
      List<StatsPdfGenerator.ReportRow> plans = new ArrayList<>(), schedules = new ArrayList<>(), todos = new ArrayList<>();
      try (Connection c = database.connection()) {
        try (PreparedStatement p = c.prepareStatement("SELECT title, description, progress, status, due_date FROM plans WHERE workspace_id = ? ORDER BY updated_at DESC")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) plans.add(new StatsPdfGenerator.ReportRow(rs.getString(1), rs.getString(2), rs.getString(3) + "%", rs.getString(4), String.valueOf(rs.getDate(5)))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(start_at), TIME_FORMAT(start_at, '%H:%i'), title, duration_minutes, status FROM schedule_items WHERE workspace_id = ? ORDER BY start_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) schedules.add(new StatsPdfGenerator.ReportRow(String.valueOf(rs.getDate(1)), rs.getString(2), rs.getString(3), rs.getString(4) + " 分钟", rs.getString(5))); } }
        try (PreparedStatement p = c.prepareStatement("SELECT DATE(due_at), TIME_FORMAT(due_at, '%H:%i'), title, priority, status FROM todos WHERE workspace_id = ? ORDER BY due_at")) { p.setBytes(1, Database.uuidBytes(workspace)); try (ResultSet rs = p.executeQuery()) { while (rs.next()) todos.add(new StatsPdfGenerator.ReportRow(String.valueOf(rs.getDate(1)), String.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5))); } }
      }
      byte[] bytes = new StatsPdfGenerator().generate(loadStats(workspace), plans, schedules, todos);
      e.getResponseHeaders().set("Content-Type", "application/pdf");
      e.getResponseHeaders().set("Content-Disposition", "attachment; filename=changlu-plan-statistics.pdf");
      e.setAttribute(RESPONSE_STATUS_ATTRIBUTE, 200);
      e.sendResponseHeaders(200, bytes.length);
      try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
    } catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void zipText(ZipOutputStream zip, String name, String text) throws IOException { zip.putNextEntry(new ZipEntry(name)); zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry(); }
  private String sheetXml(String[] headers, List<List<String>> rows) { StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>"); int row = 1; xml.append(sheetRow(row++, List.of(headers))); for (List<String> values : rows) xml.append(sheetRow(row++, values)); return xml.append("</sheetData></worksheet>").toString(); }
  private String sheetRow(int row, List<String> values) { StringBuilder xml = new StringBuilder("<row r=\"").append(row).append("\">"); for (int i = 0; i < values.size(); i++) xml.append("<c r=\"").append(column(i + 1)).append(row).append("\" t=\"inlineStr\"><is><t>").append(xmlEscape(values.get(i))).append("</t></is></c>"); return xml.append("</row>").toString(); }
  private String column(int index) { StringBuilder value = new StringBuilder(); while (index > 0) { int remainder = (index - 1) % 26; value.insert(0, (char) ('A' + remainder)); index = (index - 1) / 26; } return value.toString(); }
  private String xmlEscape(String value) { return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;"); }

  private void crud(HttpExchange e, String table) throws IOException {
    try {
      if (options(e)) return;
      String id = pathId(e);
      switch (e.getRequestMethod()) {
        case "GET" -> { if (id == null) list(e, table); else get(e, table, id); }
        case "POST" -> create(e, table);
        case "PUT", "PATCH" -> { requireId(id); update(e, table, id); }
        case "DELETE" -> { requireId(id); delete(e, table, id); }
        default -> json(e, 405, Map.of("error", "method_not_allowed"));
      }
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  // ── AI 笔记对话生成 ──
  private void noteGenerate(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      if (!model.configured()) { json(e, 503, Map.of("error", "ai_unavailable", "message", "AI 模型未配置，请设置 api.key")); return; }
      JsonObject body = body(e);
      String title = string(body, "scheduleTitle", "日程小记");
      String message = string(body, "message", "").trim();
      JsonArray messages = NotePrompt.messages(body);
      String markdown;
      String reply;
      try {
        JsonObject result = model.completeJson("note-generate", messages, 0.3, 4000, 90, 2);
        markdown = firstString(result, "markdown", "content", "text");
        reply = string(result, "reply", "");
      } catch (ModelClient.InvalidJsonException invalid) {
        // 模型直接输出了 Markdown 正文而非 JSON：把原文当作整篇笔记。
        markdown = stripFence(invalid.content());
        reply = "";
      }
      if (markdown.isBlank()) { json(e, 502, Map.of("error", "ai_generation_failed", "message", "AI 返回了空内容")); return; }
      if (reply.isBlank()) reply = message.isBlank() ? "已根据本次日程生成小记。" : "已根据你的要求调整小记。";
      JsonObject resp = new JsonObject();
      resp.addProperty("markdown", markdown);
      resp.addProperty("reply", reply);
      resp.addProperty("title", title);
      json(e, 200, resp);
    } catch (Exception ex) {
      LOG.warn("[AI笔记生成] 失败: {}", ex.getMessage());
      json(e, 502, Map.of("error", "ai_generation_failed", "message", "AI 生成笔记失败：" + ex.getMessage()));
    }
  }

  // ── 笔记图片上传 ──
  private void noteImageUpload(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      if (!"POST".equals(e.getRequestMethod())) { json(e, 405, Map.of("error", "method_not_allowed")); return; }
      String contentType = e.getRequestHeaders().getFirst("Content-Type");
      if (contentType == null || !contentType.startsWith("image/")) {
        json(e, 400, Map.of("error", "invalid_content_type", "message", "请上传图片文件（image/*）"));
        return;
      }
      int maxBytes = 10 * 1024 * 1024; // 10 MB
      byte[] raw;
      try (InputStream in = e.getRequestBody()) { raw = in.readAllBytes(); }
      if (raw.length > maxBytes) { json(e, 413, Map.of("error", "file_too_large", "message", "图片不能超过 10 MB")); return; }

      // 保存到 web/dist/uploads/notes/ 目录
      String ext = contentType.contains("png") ? "png" : contentType.contains("gif") ? "gif"
          : contentType.contains("webp") ? "webp" : contentType.contains("svg") ? "svg" : "jpg";
      String filename = UUID.randomUUID() + "." + ext;
      java.nio.file.Path uploadDir = java.nio.file.Path.of("web/dist/uploads/notes");
      java.nio.file.Files.createDirectories(uploadDir);
      java.nio.file.Files.write(uploadDir.resolve(filename), raw);

      String url = "/uploads/notes/" + filename;
      LOG.info("[笔记图片] 上传成功 {} ({} KB)", filename, raw.length / 1024);
      JsonObject resp = new JsonObject();
      resp.addProperty("url", url);
      resp.addProperty("filename", filename);
      resp.addProperty("size", raw.length);
      json(e, 201, resp);
    } catch (Exception ex) { LOG.error("[笔记图片] 上传失败: {}", ex.getMessage()); json(e, 500, Map.of("error", "upload_failed", "message", ex.getMessage())); }
  }

  private void notes(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String path = e.getRequestURI().getPath();
      String[] parts = path.split("/");
      if (parts.length >= 5 && "relations".equals(parts[4])) {
        requireId(parts[3]);
        if ("GET".equals(e.getRequestMethod())) listRelations(e, parts[3]);
        else if ("POST".equals(e.getRequestMethod())) createRelation(e, parts[3]);
        else if ("DELETE".equals(e.getRequestMethod())) deleteRelation(e, parts[3]);
        else json(e, 405, Map.of("error", "method_not_allowed"));
        return;
      }
      crud(e, "notes");
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (SQLException ex) { ex.printStackTrace(); json(e, 500, Map.of("error", "database_error", "message", ex.getMessage())); }
  }

  private void list(HttpExchange e, String table) throws SQLException, IOException {
    String softDelete = List.of("plans", "todos", "schedule_items").contains(table) ? " AND deleted_at IS NULL" : "";
    // 日历日程：不显示已删计划的日程（删除计划时其日程仍留在表里，这里过滤掉，避免日历残留孤儿项）。
    String sql = table.equals("schedule_items")
        ? "SELECT s.* FROM schedule_items s LEFT JOIN plans p ON p.id=s.plan_id "
            + "WHERE s.workspace_id = ? AND s.deleted_at IS NULL AND (s.plan_id IS NULL OR p.deleted_at IS NULL) "
            + "ORDER BY s.updated_at DESC"
        : "SELECT * FROM " + table + " WHERE workspace_id = ?" + softDelete + " ORDER BY updated_at DESC";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) rows.add(row(rs, table)); json(e, 200, rows); }
    }
  }

  private void get(HttpExchange e, String table, String id) throws SQLException, IOException {
    String softDelete = List.of("plans", "todos", "schedule_items").contains(table) ? " AND deleted_at IS NULL" : "";
    String sql = "SELECT * FROM " + table + " WHERE id = ? AND workspace_id = ?" + softDelete;
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e)));
      try (ResultSet rs = p.executeQuery()) { if (!rs.next()) { json(e, 404, Map.of("error", "not_found")); return; } json(e, 200, row(rs, table)); }
    }
  }

  private void create(HttpExchange e, String table) throws SQLException, IOException {
    JsonObject body = body(e); String id = UUID.randomUUID().toString(); UUID workspace = workspace(e), user = user(e);
    if (table.equals("schedule_items")) { json(e, 201, planExecution.createSchedule(context(e), body, "web")); return; }
    String sql;
    if (table.equals("plans")) sql = "INSERT INTO plans (id, workspace_id, owner_id, title, description, color, status, progress, due_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else if (table.equals("schedule_items")) sql = "INSERT INTO schedule_items (id, workspace_id, plan_id, created_by, title, description, start_at, duration_minutes, status, progress) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else if (table.equals("todos")) sql = "INSERT INTO todos (id, workspace_id, created_by, title, description, due_at, status, priority, reminder_minutes) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    else sql = "INSERT INTO notes (id, workspace_id, created_by, category_id, title, excerpt, content, source_type, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) {
      p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace));
      if (table.equals("plans")) { p.setBytes(3, Database.uuidBytes(user)); p.setString(4, string(body, "title", "未命名计划")); p.setString(5, nullable(body, "description")); p.setString(6, string(body, "color", "#D39A24")); p.setString(7, string(body, "status", "active")); p.setDouble(8, number(body, "progress", 0)); p.setObject(9, date(body, "dueDate")); }
      else if (table.equals("schedule_items")) { p.setBytes(3, bytesOrNull(body, "planId")); p.setBytes(4, Database.uuidBytes(user)); p.setString(5, string(body, "title", "未命名日程")); p.setString(6, nullable(body, "description")); p.setTimestamp(7, timestamp(body, "startAt")); p.setInt(8, (int) number(body, "durationMinutes", 30)); p.setString(9, string(body, "status", "pending")); p.setDouble(10, number(body, "progress", 0)); }
      else if (table.equals("todos")) { p.setBytes(3, Database.uuidBytes(user)); p.setString(4, string(body, "title", "未命名待办")); p.setString(5, nullable(body, "description")); p.setTimestamp(6, nullableTimestamp(body, "dueAt")); p.setString(7, string(body, "status", "pending")); p.setString(8, string(body, "priority", "medium")); p.setObject(9, integerOrNull(body, "reminderMinutes")); }
      else { p.setBytes(3, Database.uuidBytes(user)); p.setBytes(4, noteCategoryId(workspace, body)); p.setString(5, string(body, "title", "未命名笔记")); p.setString(6, string(body, "excerpt", "")); p.setString(7, string(body, "content", "")); p.setString(8, string(body, "sourceType", "manual")); p.setString(9, string(body, "status", "active")); }
      p.executeUpdate();
    }
    get(e, table, id);
  }

  private void update(HttpExchange e, String table, String id) throws SQLException, IOException {
    JsonObject b = body(e); List<String> columns = new ArrayList<>(); List<Object> values = new ArrayList<>();
    if (table.equals("schedule_items")) { json(e, 200, planExecution.updateSchedule(context(e), UUID.fromString(id), b, "web")); return; }
    if (table.equals("notes") && b.has("category")) { byte[] category = noteCategoryId(workspace(e), b); if (category != null) b.addProperty("categoryId", Database.bytesUuid(category).toString()); }
    Map<String, String> allowed = table.equals("plans") ? Map.of("title", "title", "description", "description", "color", "color", "status", "status", "dueDate", "due_date") : table.equals("todos") ? Map.of("title", "title", "description", "description", "dueAt", "due_at", "status", "status", "priority", "priority", "reminderMinutes", "reminder_minutes") : Map.of("title", "title", "excerpt", "excerpt", "content", "content", "status", "status", "categoryId", "category_id");
    for (Map.Entry<String, String> entry : allowed.entrySet()) if (b.has(entry.getKey())) { columns.add(entry.getValue() + " = ?"); values.add(value(b, entry.getKey())); }
    String requestedStatus = string(b, "status", null);
    if (table.equals("todos") && "done".equals(requestedStatus)) columns.add("completed_at = COALESCE(completed_at, NOW())");
    if (table.equals("todos") && requestedStatus != null && !"done".equals(requestedStatus)) columns.add("completed_at = NULL");
    if (columns.isEmpty()) { json(e, 400, Map.of("error", "no_fields")); return; }
    boolean versioned = table.equals("plans") || table.equals("todos");
    if (versioned) columns.add("version = version + 1");
    Integer expectedVersion = versioned ? integerOrNull(b, "expectedVersion") : null;
    String previousStatus = null;
    if (table.equals("todos")) try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT status FROM todos WHERE id=? AND workspace_id=? AND deleted_at IS NULL")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e))); try (ResultSet rs = p.executeQuery()) { if (rs.next()) previousStatus = rs.getString(1); } }
    String sql = "UPDATE " + table + " SET " + String.join(", ", columns) + " WHERE id = ? AND workspace_id = ?" + (versioned ? " AND deleted_at IS NULL" : "") + (expectedVersion == null ? "" : " AND version = ?");
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) { int i = 1; for (Object value : values) p.setObject(i++, value); p.setBytes(i++, Database.uuidBytes(UUID.fromString(id))); p.setBytes(i++, Database.uuidBytes(workspace(e))); if (expectedVersion != null) p.setInt(i, expectedVersion); if (p.executeUpdate() == 0) { error(e, expectedVersion == null ? 404 : 409, expectedVersion == null ? "not_found" : "version_conflict", expectedVersion == null ? "记录不存在" : "记录已被其他操作修改，请刷新后重试", false); return; }
      if (table.equals("todos") && !"done".equals(previousStatus) && "done".equals(requestedStatus)) recordManualExecution(c, workspace(e), user(e), table, UUID.fromString(id), "complete_todo", string(b, "note", "手动完成待办"));
      if (table.equals("todos") && "delayed".equals(requestedStatus)) recordManualExecution(c, workspace(e), user(e), table, UUID.fromString(id), "delay_todo", string(b, "note", "手动延期待办")); }
    get(e, table, id);
  }

  private void delete(HttpExchange e, String table, String id) throws SQLException, IOException {
    if (List.of("plans", "todos", "schedule_items").contains(table)) {
      try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("UPDATE " + table + " SET deleted_at=NOW(),purge_after=DATE_ADD(NOW(),INTERVAL 30 DAY),version=version+1 WHERE id=? AND workspace_id=? AND deleted_at IS NULL")) {
        p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e))); json(e, p.executeUpdate() == 0 ? 404 : 204, Map.of("deleted", true));
      }
      return;
    }
    try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM " + table + " WHERE id = ? AND workspace_id = ?")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(id))); p.setBytes(2, Database.uuidBytes(workspace(e))); json(e, p.executeUpdate() == 0 ? 404 : 204, Map.of("deleted", true)); }
  }

  private void recordManualExecution(Connection c, UUID workspace, UUID user, String table, UUID entityId, String action, String note) throws SQLException {
    try (PreparedStatement p = c.prepareStatement("INSERT INTO execution_records (id, workspace_id, user_id, entity_type, entity_id, action_type, note, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW())")) {
      p.setBytes(1, Database.uuidBytes(UUID.randomUUID())); p.setBytes(2, Database.uuidBytes(workspace)); p.setBytes(3, Database.uuidBytes(user)); p.setString(4, table); p.setBytes(5, Database.uuidBytes(entityId)); p.setString(6, action); p.setString(7, note); p.executeUpdate();
    }
  }

  private void listRelations(HttpExchange e, String noteId) throws SQLException, IOException { String sql = "SELECT n.id, n.title, r.relation_type FROM note_relations r JOIN notes n ON n.id = r.to_note_id WHERE r.from_note_id = ? UNION SELECT n.id, n.title, r.relation_type FROM note_relations r JOIN notes n ON n.id = r.from_note_id WHERE r.to_note_id = ?"; try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement(sql)) { p.setBytes(1, Database.uuidBytes(UUID.fromString(noteId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(noteId))); try (ResultSet rs = p.executeQuery()) { List<JsonObject> rows = new ArrayList<>(); while (rs.next()) { JsonObject row = new JsonObject(); row.addProperty("id", Database.id(rs, "id")); row.addProperty("title", rs.getString("title")); row.addProperty("relationType", rs.getString("relation_type")); rows.add(row); } json(e, 200, rows); } } }
  private void createRelation(HttpExchange e, String fromId) throws SQLException, IOException { JsonObject b = body(e); String toId = string(b, "toNoteId", null); requireId(toId); try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("INSERT INTO note_relations (from_note_id, to_note_id, relation_type) VALUES (?, ?, ?)")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(fromId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(toId))); p.setString(3, string(b, "relationType", "related")); p.executeUpdate(); } json(e, 201, Map.of("fromNoteId", fromId, "toNoteId", toId, "relationType", string(b, "relationType", "related"))); }
  private void deleteRelation(HttpExchange e, String fromId) throws SQLException, IOException { JsonObject b = body(e); requireId(string(b, "toNoteId", null)); try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("DELETE FROM note_relations WHERE from_note_id = ? AND to_note_id = ?")) { p.setBytes(1, Database.uuidBytes(UUID.fromString(fromId))); p.setBytes(2, Database.uuidBytes(UUID.fromString(string(b, "toNoteId", null)))); json(e, p.executeUpdate() == 0 ? 404 : 204, Map.of("deleted", true)); } }

  private JsonObject row(ResultSet rs, String table) throws SQLException { JsonObject o = new JsonObject(); o.addProperty("id", Database.id(rs, "id")); o.addProperty("title", rs.getString("title")); if (table.equals("plans")) { o.addProperty("description", rs.getString("description")); o.addProperty("color", rs.getString("color")); o.addProperty("status", rs.getString("status")); o.addProperty("progress", rs.getDouble("progress")); o.addProperty("taskProgress", rs.getDouble("task_progress")); o.addProperty("effortProgress", rs.getDouble("effort_progress")); o.addProperty("version", rs.getInt("version")); java.sql.Date dueDate = rs.getDate("due_date"); o.addProperty("dueDate", dueDate == null ? null : dueDate.toString()); } else if (table.equals("schedule_items")) { o.addProperty("description", rs.getString("description")); o.addProperty("startAt", String.valueOf(rs.getTimestamp("start_at"))); o.addProperty("durationMinutes", rs.getInt("duration_minutes")); o.addProperty("status", rs.getString("status")); o.addProperty("progress", rs.getDouble("progress")); o.addProperty("planId", rs.getBytes("plan_id") == null ? null : Database.id(rs, "plan_id")); o.addProperty("stageId", rs.getBytes("stage_id") == null ? null : Database.id(rs, "stage_id")); o.addProperty("taskId", rs.getBytes("task_id") == null ? null : Database.id(rs, "task_id")); o.addProperty("version", rs.getInt("version")); } else if (table.equals("todos")) { o.addProperty("description", rs.getString("description")); Timestamp dueAt = rs.getTimestamp("due_at"); o.addProperty("dueAt", dueAt == null ? null : dueAt.toString()); o.addProperty("status", rs.getString("status")); o.addProperty("priority", rs.getString("priority")); o.addProperty("reminderMinutes", (Integer) rs.getObject("reminder_minutes")); o.addProperty("version", rs.getInt("version")); } else { o.addProperty("excerpt", rs.getString("excerpt")); o.addProperty("content", rs.getString("content")); o.addProperty("sourceType", rs.getString("source_type")); o.addProperty("status", rs.getString("status")); byte[] category = rs.getBytes("category_id"); o.addProperty("categoryId", category == null ? null : Database.bytesUuid(category).toString()); o.addProperty("category", category == null ? "未分类" : categoryName(category)); } return o; }

  private byte[] noteCategoryId(UUID workspace, JsonObject body) throws SQLException {
    String category = string(body, "category", null); if (category == null || category.isBlank()) return bytesOrNull(body, "categoryId");
    UUID id = UUID.randomUUID();
    try (Connection c = database.connection(); PreparedStatement insert = c.prepareStatement("INSERT IGNORE INTO note_categories (id, workspace_id, name) VALUES (?, ?, ?)")) { insert.setBytes(1, Database.uuidBytes(id)); insert.setBytes(2, Database.uuidBytes(workspace)); insert.setString(3, category.trim()); insert.executeUpdate(); }
    try (Connection c = database.connection(); PreparedStatement find = c.prepareStatement("SELECT id FROM note_categories WHERE workspace_id = ? AND name = ?")) { find.setBytes(1, Database.uuidBytes(workspace)); find.setString(2, category.trim()); try (ResultSet rs = find.executeQuery()) { return rs.next() ? rs.getBytes(1) : null; } }
  }

  private String categoryName(byte[] id) throws SQLException { try (Connection c = database.connection(); PreparedStatement p = c.prepareStatement("SELECT name FROM note_categories WHERE id = ?")) { p.setBytes(1, id); try (ResultSet rs = p.executeQuery()) { return rs.next() ? rs.getString(1) : "未分类"; } } }

  private boolean options(HttpExchange e) throws IOException { if (!"OPTIONS".equals(e.getRequestMethod())) return false; cors(e); e.sendResponseHeaders(204, -1); e.close(); return true; }
  private void json(HttpExchange e, int status, Object value) throws IOException { e.setAttribute(RESPONSE_STATUS_ATTRIBUTE, status); cors(e); byte[] bytes = gson.toJson(value).getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8"); e.sendResponseHeaders(status, status == 204 ? -1 : bytes.length); if (status != 204) try (OutputStream out = e.getResponseBody()) { out.write(bytes); } else e.close(); }
  private void cors(HttpExchange e) { Headers h = e.getResponseHeaders(); h.set("Access-Control-Allow-Origin", "*"); h.set("Access-Control-Allow-Headers", "Content-Type, X-Workspace-Id, X-User-Id, X-File-Name"); h.set("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS"); }
  private JsonObject body(HttpExchange e) throws IOException {
    try (InputStream in = e.getRequestBody()) {
      String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      JsonObject body = raw.isBlank() ? new JsonObject() : gson.fromJson(raw, JsonObject.class);
      return body;
    }
  }

  private void scheduleSubresource(HttpExchange e) throws IOException {
    try {
      if (options(e)) return;
      String[] parts = e.getRequestURI().getPath().split("/");
      // /api/schedules/{id} 仍是日程 CRUD，只有 /materials 才属于资料子资源。
      if (parts.length == 4 && !parts[3].isBlank()) {
        crud(e, "schedule_items");
        return;
      }
      if (parts.length < 5 || parts[3].isBlank() || !"materials".equals(parts[4])) {
        json(e, 404, Map.of("error", "schedule_materials_not_found"));
        return;
      }
      if (!"GET".equals(e.getRequestMethod())) {
        json(e, 405, Map.of("error", "method_not_allowed"));
        return;
      }
      boolean refresh = e.getRequestURI().getRawQuery() != null && e.getRequestURI().getRawQuery().contains("refresh=true");
      json(e, 200, scheduleMaterials.load(context(e), UUID.fromString(parts[3]), refresh));
    } catch (IllegalArgumentException ex) { json(e, 400, Map.of("error", ex.getMessage())); }
      catch (Exception ex) { LOG.warn("[日程资料] 获取失败: {}", ex.getMessage()); json(e, 502, Map.of("error", "schedule_materials_unavailable", "message", "暂时无法获取日程资料")); }
  }

  private String safeBody(JsonObject body) {
    JsonObject safe = body.deepCopy();
    for (String key : new ArrayList<>(safe.keySet())) {
      String normalized = key.toLowerCase();
      if (normalized.contains("password") || normalized.contains("token") || normalized.contains("secret")
          || normalized.contains("apikey") || normalized.contains("api_key")
          || normalized.contains("authorization") || normalized.equals("avatarurl")) {
        safe.addProperty(key, "[已脱敏]");
      }
    }
    return abbreviate(gson.toJson(safe), 4000);
  }

  private String abbreviate(String value, int maxLength) {
    if (value == null || value.length() <= maxLength) return value;
    return value.substring(0, maxLength) + "... [已截断，原长度=" + value.length() + "]";
  }
  private UUID workspace(HttpExchange e) { return uuidHeader(e, "X-Workspace-Id", Database.DEFAULT_WORKSPACE_ID); }
  private UUID user(HttpExchange e) { return uuidHeader(e, "X-User-Id", Database.DEFAULT_USER_ID); }
  private Database.Context context(HttpExchange e) { return new Database.Context(user(e), workspace(e)); }
  private UUID uuidHeader(HttpExchange e, String name, UUID fallback) { String value = e.getRequestHeaders().getFirst(name); return value == null || value.isBlank() ? fallback : UUID.fromString(value); }
  private String pathId(HttpExchange e) { String[] p = e.getRequestURI().getPath().split("/"); return p.length > 3 && !p[3].isBlank() ? p[3] : null; }
  private void requireId(String id) { if (id == null || id.isBlank()) throw new IllegalArgumentException("id_required"); UUID.fromString(id); }
  private String string(JsonObject o, String key, String fallback) { JsonElement v = o.get(key); return v == null || v.isJsonNull() ? fallback : v.getAsString(); }
  /** 按序取第一个非空字符串字段，容忍模型把笔记塞进 content/text 等字段。 */
  private String firstString(JsonObject o, String... keys) {
    for (String key : keys) {
      JsonElement v = o.get(key);
      if (v != null && v.isJsonPrimitive() && !v.getAsString().isBlank()) return v.getAsString();
    }
    return "";
  }
  /** 去掉模型用 ``` 包裹的 Markdown 围栏。 */
  private String stripFence(String value) {
    String s = value.trim();
    if (s.startsWith("```json")) s = s.substring("```json".length());
    else if (s.startsWith("```markdown")) s = s.substring("```markdown".length());
    else if (s.startsWith("```")) s = s.substring(3);
    if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
    return s.trim();
  }
  private String nullable(JsonObject o, String key) { return string(o, key, null); }
  private double number(JsonObject o, String key, double fallback) { String v = string(o, key, null); return v == null ? fallback : Double.parseDouble(v); }
  private Object value(JsonObject o, String key) { if (key.endsWith("At")) return nullableTimestamp(o, key); if (key.equals("dueDate")) return date(o, key); if (key.equals("progress")) return number(o, key, 0); if (key.equals("durationMinutes") || key.equals("reminderMinutes")) return integerOrNull(o, key); if (key.equals("planId") || key.equals("categoryId")) return bytesOrNull(o, key); return nullable(o, key); }
  private byte[] bytesOrNull(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : Database.uuidBytes(UUID.fromString(v)); }
  private Integer integerOrNull(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : Integer.valueOf(v); }
  private java.sql.Date date(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : java.sql.Date.valueOf(LocalDate.parse(v)); }
  private Timestamp timestamp(JsonObject o, String key) { String v = string(o, key, null); if (v == null) throw new IllegalArgumentException(key + "_required"); return Timestamp.valueOf(LocalDateTime.parse(v.replace("Z", ""))); }
  private Timestamp nullableTimestamp(JsonObject o, String key) { String v = string(o, key, null); return v == null ? null : timestamp(o, key); }
  private void error(HttpExchange e, int status, String code, String message, boolean retryable) throws IOException {
    JsonObject result = new JsonObject(); result.addProperty("error", code); result.addProperty("message", message == null || message.isBlank() ? code : message);
    result.add("details", new JsonObject()); result.addProperty("retryable", retryable); json(e, status, result);
  }
}
