package com.example.ilink.adapter.inbound.http;

import com.example.ilink.adapter.outbound.web.WebArtifactStore;
import com.example.ilink.adapter.outbound.web.WebEventBroker;
import com.example.ilink.adapter.outbound.web.WebReplyChannel;
import com.example.ilink.adapter.inbound.wechat.LoginQrPage;
import com.example.ilink.application.conversation.ChatSession;
import com.example.ilink.application.conversation.ChatHistoryStore;
import com.example.ilink.application.conversation.ConversationSession;
import com.example.ilink.application.conversation.SessionService;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.AgentContext;
import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.messaging.ChannelType;
import com.example.ilink.application.messaging.IncomingMessage;
import com.example.ilink.application.messaging.MessagePart;
import com.example.ilink.application.messaging.MessageProcessor;
import com.example.ilink.application.messaging.MessageSerialExecutor;
import com.example.ilink.application.messaging.RequestLogContext;
import com.example.ilink.application.integration.WechatWebBridge;
import com.example.ilink.bootstrap.Config;
import com.example.ilink.platform.persistence.MySqlStore;
import com.example.ilink.platform.workspace.WorkspaceFileService;
import com.example.ilink.capabilities.dashboard.DailyDashboardService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Local Web chat adapter that reuses the channel-neutral message processor. */
public final class WebChatServer implements AutoCloseable {

    private static final String PAGE_RESOURCE = "/templates/web-chat.html";
    private static final String CSS_RESOURCE = "/static/css/web-chat.css";
    private static final String JS_RESOURCE = "/static/js/web-chat.js";
    private static final String SHELL_CSS_RESOURCE = "/static/css/web-shell.css";
    private static final String SHELL_JS_RESOURCE = "/static/js/web-shell.js";
    private static final int MAX_TEXT_CHARS = 20_000;
    private static final DateTimeFormatter SESSION_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final MessageProcessor messageProcessor;
    private final MessageSerialExecutor messageExecutor;
    private final SessionService sessionService;
    private final UserSessionStore sessions;
    private final ChatHistoryStore chatHistory;
    private final MySqlStore database;
    private final WebReplyChannel replyChannel;
    private final WebEventBroker events;
    private final WebArtifactStore artifacts;
    private final WechatWebBridge wechatBridge;
    private final WorkspaceFileService workspaceFiles;
    private final LoginQrPage loginQrPage;
    private final DailyDashboardService dashboardService;
    private final DailyDashboardServer dailyDashboardServer;
    private final SessionManagementServer sessionManagementServer;
    private final WebTaskRegistry tasks = new WebTaskRegistry();
    private final Map<String, PendingDelivery> pendingDeliveries = new ConcurrentHashMap<>();
    private final Gson gson = new Gson();
    private final ExecutorService httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean running;
    private HttpServer server;

    public WebChatServer(MessageProcessor messageProcessor,
                         MessageSerialExecutor messageExecutor,
                         SessionService sessionService,
                         UserSessionStore sessions,
                         ChatHistoryStore chatHistory,
                         MySqlStore database,
                         WebReplyChannel replyChannel,
                         WebEventBroker events,
                         WebArtifactStore artifacts) {
        this(messageProcessor, messageExecutor, sessionService, sessions, chatHistory, database,
                replyChannel, events, artifacts, null, null, null, null, null, null);
    }

    public WebChatServer(MessageProcessor messageProcessor,
                         MessageSerialExecutor messageExecutor,
                         SessionService sessionService,
                         UserSessionStore sessions,
                         ChatHistoryStore chatHistory,
                         MySqlStore database,
                         WebReplyChannel replyChannel,
                         WebEventBroker events,
                         WebArtifactStore artifacts,
                         WechatWebBridge wechatBridge,
                         WorkspaceFileService workspaceFiles,
                         LoginQrPage loginQrPage,
                         DailyDashboardService dashboardService) {
        this(messageProcessor, messageExecutor, sessionService, sessions, chatHistory, database,
                replyChannel, events, artifacts, wechatBridge, workspaceFiles, loginQrPage,
                dashboardService, null, null);
    }

    public WebChatServer(MessageProcessor messageProcessor,
                         MessageSerialExecutor messageExecutor,
                         SessionService sessionService,
                         UserSessionStore sessions,
                         ChatHistoryStore chatHistory,
                         MySqlStore database,
                         WebReplyChannel replyChannel,
                         WebEventBroker events,
                         WebArtifactStore artifacts,
                         WechatWebBridge wechatBridge,
                         WorkspaceFileService workspaceFiles,
                         LoginQrPage loginQrPage,
                         DailyDashboardService dashboardService,
                         DailyDashboardServer dailyDashboardServer,
                         SessionManagementServer sessionManagementServer) {
        this.messageProcessor = messageProcessor;
        this.messageExecutor = messageExecutor;
        this.sessionService = sessionService;
        this.sessions = sessions;
        this.chatHistory = chatHistory;
        this.database = database;
        this.replyChannel = replyChannel;
        this.events = events;
        this.artifacts = artifacts;
        this.wechatBridge = wechatBridge;
        this.workspaceFiles = workspaceFiles;
        this.loginQrPage = loginQrPage;
        this.dashboardService = dashboardService;
        this.dailyDashboardServer = dailyDashboardServer;
        this.sessionManagementServer = sessionManagementServer;
    }

    public void start() {
        if (!Config.WEB_CHAT_ENABLED) return;
        try {
            server = HttpServer.create(new InetSocketAddress(
                    Config.WEB_CHAT_BIND_ADDRESS, Config.WEB_CHAT_PORT), 0);
            server.createContext("/", this::handle);
            server.setExecutor(httpExecutor);
            running = true;
            server.start();
            System.out.println("[Web Bot] 已启动: " + url());
        } catch (IOException error) {
            running = false;
            server = null;
            System.err.println("[Web Bot] 启动失败: " + error.getMessage());
        }
    }

    public String url() {
        if (server == null) return "";
        String host = "0.0.0.0".equals(Config.WEB_CHAT_BIND_ADDRESS)
                ? "127.0.0.1" : Config.WEB_CHAT_BIND_ADDRESS;
        return "http://" + host + ":" + Config.WEB_CHAT_PORT + "/web";
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(method) && "/web/wechat-login".equals(path)) {
                sendWechatLoginPage(exchange);
                return;
            }
            if ("GET".equals(method) && ("/".equals(path) || path.matches("/web(?:/(?:wechat|plan|sessions|login|files))?/?"))) {
                sendResource(exchange, "text/html; charset=utf-8", PAGE_RESOURCE);
                return;
            }
            if ("GET".equals(method) && "/static/css/web-chat.css".equals(path)) {
                sendResource(exchange, "text/css; charset=utf-8", CSS_RESOURCE);
                return;
            }
            if ("GET".equals(method) && "/static/js/web-chat.js".equals(path)) {
                sendResource(exchange, "text/javascript; charset=utf-8", JS_RESOURCE);
                return;
            }
            if ("GET".equals(method) && "/static/css/web-shell.css".equals(path)) {
                sendResource(exchange, "text/css; charset=utf-8", SHELL_CSS_RESOURCE);
                return;
            }
            if ("GET".equals(method) && "/static/js/web-shell.js".equals(path)) {
                sendResource(exchange, "text/javascript; charset=utf-8", SHELL_JS_RESOURCE);
                return;
            }
            if ("GET".equals(method) && "/static/css/qrcode.css".equals(path)) {
                sendResource(exchange, "text/css; charset=utf-8", "/static/css/qrcode.css");
                return;
            }
            if ("GET".equals(method) && "/static/js/qrcode.js".equals(path)) {
                sendResource(exchange, "text/javascript; charset=utf-8", "/static/js/qrcode.js");
                return;
            }
            if ("GET".equals(method) && "/api/web/events".equals(path)) {
                streamEvents(exchange, client(exchange));
                return;
            }
            if (path.startsWith("/api/web/wechat")) {
                handleWechatApi(exchange, client(exchange), path.substring("/api/web/wechat".length()));
                return;
            }
            if (path.startsWith("/api/web/workspace")) {
                handleWorkspaceApi(exchange, client(exchange), path.substring("/api/web/workspace".length()));
                return;
            }
            if ("GET".equals(method) && "/api/web/login".equals(path)) {
                sendLoginStatus(exchange, client(exchange));
                return;
            }
            if ("GET".equals(method) && "/api/web/plan".equals(path)) {
                if (dashboardService == null) throw new HttpError(503, "七日计划服务不可用");
                sendJson(exchange, 200, dashboardService.snapshot(client(exchange).userId));
                return;
            }
            if ("GET".equals(method) && "/api/web/navigation".equals(path)) {
                sendNavigation(exchange, client(exchange));
                return;
            }
            if ("POST".equals(method) && "/api/web/messages".equals(path)) {
                acceptText(exchange, client(exchange));
                return;
            }
            if ("POST".equals(method) && "/api/web/files".equals(path)) {
                acceptFile(exchange, client(exchange));
                return;
            }
            if ("POST".equals(method) && "/api/web/cancel".equals(path)) {
                cancel(exchange, client(exchange));
                return;
            }
            if ("GET".equals(method) && "/api/web/tasks".equals(path)) {
                sendJson(exchange, 200, taskList(client(exchange)));
                return;
            }
            String taskPrefix = "/api/web/tasks/";
            if ("POST".equals(method) && path.startsWith(taskPrefix) && path.endsWith("/resume")) {
                resumeTask(exchange, client(exchange),
                        path.substring(taskPrefix.length(), path.length() - "/resume".length()));
                return;
            }
            if ("/api/web/sessions".equals(path)) {
                if ("GET".equals(method)) {
                    sendJson(exchange, 200, sessions(client(exchange)));
                    return;
                }
                if ("POST".equals(method)) {
                    createSession(exchange, client(exchange));
                    return;
                }
            }
            String sessionPrefix = "/api/web/sessions/";
            if (path.startsWith(sessionPrefix)) {
                String suffix = path.substring(sessionPrefix.length());
                if ("GET".equals(method) && suffix.endsWith("/messages")) {
                    sendJson(exchange, 200, messages(client(exchange),
                            suffix.substring(0, suffix.length() - "/messages".length())));
                    return;
                }
                if ("POST".equals(method) && suffix.endsWith("/use")) {
                    useSession(exchange, client(exchange),
                            suffix.substring(0, suffix.length() - "/use".length()));
                    return;
                }
                String[] messageAction = suffix.split("/messages/", 2);
                if ("POST".equals(method) && messageAction.length == 2
                        && messageAction[1].endsWith("/rerun")) {
                    String messageId = messageAction[1].substring(
                            0, messageAction[1].length() - "/rerun".length());
                    rerunEditedMessage(exchange, client(exchange), messageAction[0], messageId);
                    return;
                }
                if ("PATCH".equals(method) && !suffix.contains("/")) {
                    renameSession(exchange, client(exchange), suffix);
                    return;
                }
                if ("DELETE".equals(method) && !suffix.contains("/")) {
                    deleteSession(exchange, client(exchange), suffix);
                    return;
                }
            }
            String artifactPrefix = "/api/web/artifacts/";
            if ("GET".equals(method) && path.startsWith(artifactPrefix)) {
                sendArtifact(exchange, client(exchange), path.substring(artifactPrefix.length()));
                return;
            }
            throw new HttpError(404, "请求的资源不存在");
        } catch (HttpError error) {
            sendError(exchange, error.status, error.getMessage());
        } catch (IllegalArgumentException error) {
            sendError(exchange, 400, error.getMessage());
        } catch (Exception error) {
            System.err.println("[Web Bot] 请求处理失败: " + error.getMessage());
            sendError(exchange, 500, "服务器内部错误");
        } finally {
            exchange.close();
        }
    }

    private void acceptText(HttpExchange exchange, Client client) throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(new String(
                    readBody(exchange, 64 * 1024L), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new HttpError(400, "请求体不是有效的 JSON");
        }
        String text = body.has("text") ? body.get("text").getAsString().trim() : "";
        if (text.isBlank()) throw new HttpError(400, "消息不能为空");
        if (text.length() > MAX_TEXT_CHARS) throw new HttpError(413, "消息内容过长");
        String sessionId = body.has("sessionId") ? body.get("sessionId").getAsString() : "";
        WebTaskRegistry.Task task = submit(client, requireOwnedSession(client, sessionId),
                List.of(new MessagePart.Text(text)));
        boolean synced = false;
        if (body.has("syncWechat") && body.get("syncWechat").getAsBoolean() && wechatBridge != null) {
            try {
                synced = wechatBridge.syncWebInput(client.userId, text,
                        body.has("syncReplies") && body.get("syncReplies").getAsBoolean());
            } catch (Exception error) {
                System.err.println(webLog("微信同步失败", client, task) + " error=" + RequestLogContext.error(error));
            }
        }
        System.out.println(webLog("任务接收", client, task) + " input=text chars=" + text.length()
                + " preview=" + RequestLogContext.preview(text));
        JsonObject response = new JsonObject();
        response.addProperty("accepted", true);
        response.addProperty("requestId", task.id());
        response.addProperty("sessionId", task.sessionId());
        response.addProperty("wechatSynced", synced);
        sendJson(exchange, 202, response);
    }

    private void acceptFile(HttpExchange exchange, Client client) throws IOException {
        String encodedName = exchange.getRequestHeaders().getFirst("X-File-Name");
        if (encodedName == null || encodedName.isBlank()) throw new HttpError(400, "缺少文件名");
        String fileName = URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
        fileName = java.nio.file.Path.of(fileName).getFileName().toString();
        byte[] content = readBody(exchange, Config.WEB_CHAT_MAX_UPLOAD_BYTES);
        if (content.length == 0) throw new HttpError(400, "文件内容为空");
        String contentType = value(exchange.getRequestHeaders().getFirst("Content-Type")).toLowerCase(Locale.ROOT);
        MessagePart part = isImage(contentType, fileName)
                ? new MessagePart.Image(content, fileName)
                : new MessagePart.File(content, fileName);
        String sessionId = value(exchange.getRequestHeaders().getFirst("X-Session-Id"));
        WebTaskRegistry.Task task = submit(client, requireOwnedSession(client, sessionId), List.of(part));
        System.out.println(webLog("任务接收", client, task) + " input="
                + (part instanceof MessagePart.Image ? "image" : "file")
                + " file=" + RequestLogContext.preview(fileName) + " bytes=" + content.length);
        JsonObject response = new JsonObject();
        response.addProperty("accepted", true);
        response.addProperty("fileName", fileName);
        response.addProperty("requestId", task.id());
        response.addProperty("sessionId", task.sessionId());
        sendJson(exchange, 202, response);
    }

    private WebTaskRegistry.Task submit(Client client, String sessionId, List<MessagePart> parts) {
        WebTaskRegistry.Task task = tasks.create(client.userId, sessionId, parts);
        queue(client, task);
        return task;
    }

    private void queue(Client client, WebTaskRegistry.Task task) {
        messageExecutor.execute(sessionKey(client.userId, task.sessionId()), () -> {
            long executionGeneration = task.start();
            if (executionGeneration < 0L) return;
            try (RequestLogContext.Scope ignored = RequestLogContext.open(
                    ChannelType.WEB, client.userId, task.sessionId(), task.id())) {
                AgentContext context = AgentContext.web(client.userId, task.sessionId(),
                        replyChannel, client.workspaceId);
                IncomingMessage message = new IncomingMessage(context.identity(), task.parts());
                replyChannel.beginRequest(client.userId, task.sessionId(), task.id());
                WebTaskRegistry.Snapshot started = task.snapshot();
                System.out.println(RequestLogContext.prefix("任务开始") + " attempt=" + started.attempt()
                        + " input=" + describeParts(task.parts()));
                publishTaskEvent(task, AgentEvent.Type.STATUS,
                        started.attempt() > 1 ? "正在继续任务" : "正在处理", "working");
                try {
                    messageProcessor.process(context, message);
                    String completedText = replyChannel.consumeCompletedText(task.id());
                    if (!task.isCurrent(executionGeneration)
                            || task.state() == WebTaskRegistry.State.PAUSED) return;
                    task.complete(executionGeneration);
                    WebTaskRegistry.Snapshot completed = task.snapshot();
                    System.out.println(RequestLogContext.prefix("任务完成")
                            + " elapsed_ms=" + completed.elapsedMs()
                            + " reply_chars=" + (completedText == null ? 0 : completedText.length()));
                    String generatedTitle = WebSessionTitleGenerator.generate(task.parts(), completedText);
                    if (!generatedTitle.isBlank()) {
                        sessionService.autoNameSession(client.userId, task.sessionId(), generatedTitle);
                    }
                    publishTaskEvent(task, AgentEvent.Type.STATUS, "处理完成", "idle");
                } catch (CancellationException error) {
                    if (task.isCurrent(executionGeneration)
                            && task.state() != WebTaskRegistry.State.PAUSED) {
                        task.fail(executionGeneration, "任务已取消");
                        System.out.println(RequestLogContext.prefix("任务取消")
                                + " elapsed_ms=" + task.snapshot().elapsedMs());
                        publishTaskEvent(task, AgentEvent.Type.ERROR, "任务已取消", "idle");
                    }
                } catch (Exception error) {
                    if (Thread.currentThread().isInterrupted()
                            || !task.isCurrent(executionGeneration)
                            || task.state() == WebTaskRegistry.State.PAUSED) return;
                    task.fail(executionGeneration, "消息处理失败");
                    System.err.println(RequestLogContext.prefix("任务失败")
                            + " elapsed_ms=" + task.snapshot().elapsedMs()
                            + " error=" + RequestLogContext.error(error));
                    publishTaskEvent(task, AgentEvent.Type.ERROR, "消息处理失败，请稍后重试", "idle");
                } finally {
                    replyChannel.consumeCompletedText(task.id());
                    replyChannel.endRequest();
                }
            }
        });
    }

    private JsonObject sessions(Client client) {
        ConversationSession current = sessions.getCurrentSession(client.userId);
        JsonObject response = new JsonObject();
        response.addProperty("activeSessionId", current.sessionId());
        response.addProperty("databaseAvailable", database.isAvailable());
        JsonArray items = new JsonArray();
        List<MySqlStore.SessionRow> rows = sessionService.listSessions(client.userId);
        for (MySqlStore.SessionRow row : rows) items.add(sessionJson(row));
        if (rows.stream().noneMatch(row -> current.sessionId().equals(row.sessionId()))) {
            JsonObject item = new JsonObject();
            item.addProperty("sessionId", current.sessionId());
            item.addProperty("title", "新会话");
            item.addProperty("status", "ACTIVE");
            item.addProperty("lastActiveTime", format(current.lastActiveAt()));
            items.add(item);
        }
        response.add("sessions", items);
        return response;
    }

    private void createSession(HttpExchange exchange, Client client) throws IOException {
        String sessionId = sessionService.createNewSession(client.userId);
        JsonObject response = new JsonObject();
        response.addProperty("sessionId", sessionId);
        sendJson(exchange, 201, response);
    }

    private void useSession(HttpExchange exchange, Client client, String sessionId) throws IOException {
        boolean switched = sessionService.switchSession(client.userId, sessionId);
        if (!switched) throw new HttpError(404, "会话不存在");
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        sendJson(exchange, 200, response);
    }

    private void renameSession(HttpExchange exchange, Client client, String sessionId) throws IOException {
        JsonObject body;
        try {
            body = JsonParser.parseString(new String(
                    readBody(exchange, 4 * 1024L), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new HttpError(400, "请求体不是有效的 JSON");
        }
        String title = body.has("title") ? body.get("title").getAsString().trim() : "";
        if (title.isBlank()) throw new HttpError(400, "会话名称不能为空");
        if (title.length() > 100) throw new HttpError(413, "会话名称过长");
        if (!sessionService.renameSession(client.userId, sessionId, title)) {
            throw new HttpError(404, "会话不存在");
        }
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("title", title);
        sendJson(exchange, 200, response);
    }

    private void deleteSession(HttpExchange exchange, Client client, String sessionId) throws IOException {
        pauseSession(client, sessionId);
        String activeSessionId = sessionService.deleteSession(client.userId, sessionId);
        if (activeSessionId == null) throw new HttpError(404, "会话不存在");
        chatHistory.invalidateSession(client.userId, sessionId);
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("activeSessionId", activeSessionId);
        sendJson(exchange, 200, response);
    }

    private void cancel(HttpExchange exchange, Client client) throws IOException {
        JsonObject body = optionalJsonBody(exchange, 8 * 1024L);
        String sessionId = body.has("sessionId") ? body.get("sessionId").getAsString() : "";
        sessionId = requireOwnedSession(client, sessionId);
        List<WebTaskRegistry.Task> paused = pauseSession(client, sessionId);
        JsonObject response = new JsonObject();
        response.addProperty("cancelled", !paused.isEmpty());
        response.addProperty("sessionId", sessionId);
        response.add("tasks", gson.toJsonTree(paused.stream().map(WebTaskRegistry.Task::snapshot).toList()));
        sendJson(exchange, 200, response);
    }

    private List<WebTaskRegistry.Task> pauseSession(Client client, String sessionId) {
        List<WebTaskRegistry.Task> paused = tasks.pauseSession(client.userId, sessionId);
        paused.forEach(task -> {
            replyChannel.cancel(task.id());
            System.out.println(webLog("任务暂停", client, task)
                    + " elapsed_ms=" + task.snapshot().elapsedMs());
            publishTaskEvent(task, AgentEvent.Type.STATUS, "任务已暂停，可继续", "paused");
        });
        messageExecutor.cancel(sessionKey(client.userId, sessionId));
        return paused;
    }

    private void resumeTask(HttpExchange exchange, Client client, String requestId) throws IOException {
        WebTaskRegistry.Task task = tasks.findOwned(requestId, client.userId)
                .orElseThrow(() -> new HttpError(404, "任务不存在"));
        if (!task.resume()) throw new HttpError(409, "只有已暂停的任务可以继续");
        System.out.println(webLog("任务继续", client, task)
                + " attempt=" + (task.snapshot().attempt() + 1));
        queue(client, task);
        JsonObject response = new JsonObject();
        response.addProperty("resumed", true);
        response.add("task", gson.toJsonTree(task.snapshot()));
        sendJson(exchange, 202, response);
    }

    private JsonObject taskList(Client client) {
        JsonObject response = new JsonObject();
        response.add("tasks", gson.toJsonTree(tasks.snapshots(client.userId)));
        return response;
    }

    private void rerunEditedMessage(HttpExchange exchange, Client client,
                                    String sessionId, String rawMessageId) throws IOException {
        sessionId = requireOwnedSession(client, sessionId);
        long messageId;
        try {
            messageId = Long.parseLong(rawMessageId);
        } catch (NumberFormatException error) {
            throw new HttpError(400, "消息标识无效");
        }
        JsonObject body = optionalJsonBody(exchange, 64 * 1024L);
        String text = body.has("text") ? body.get("text").getAsString().trim() : "";
        if (text.isBlank()) throw new HttpError(400, "消息不能为空");
        if (text.length() > MAX_TEXT_CHARS) throw new HttpError(413, "消息内容过长");
        pauseSession(client, sessionId);
        if (!database.truncateSessionFromUserMessage(sessionId, client.userId, messageId)) {
            throw new HttpError(404, "只能修改当前会话中的用户消息");
        }
        chatHistory.invalidateSession(client.userId, sessionId);
        WebTaskRegistry.Task task = submit(client, sessionId, List.of(new MessagePart.Text(text)));
        System.out.println(webLog("任务重跑", client, task) + " message_id=" + messageId
                + " chars=" + text.length() + " preview=" + RequestLogContext.preview(text));
        JsonObject response = new JsonObject();
        response.addProperty("rerun", true);
        response.addProperty("requestId", task.id());
        response.addProperty("sessionId", sessionId);
        sendJson(exchange, 202, response);
    }

    private JsonObject messages(Client client, String sessionId) {
        JsonObject response = new JsonObject();
        response.addProperty("sessionId", sessionId);
        JsonArray items = new JsonArray();
        if (database.isAvailable()) {
            MySqlStore.SessionRow row = database.findSession(sessionId);
            if (row == null || !client.userId.equals(row.wechatId())) {
                throw new HttpError(404, "会话不存在");
            }
            for (MySqlStore.ChatEntry entry : database.loadSessionMessages(sessionId, 200)) {
                items.add(historyMessage(client, entry));
            }
        } else if (!sessionId.equals(sessions.getCurrentSession(client.userId).sessionId())) {
            throw new HttpError(404, "会话不存在");
        }
        response.add("messages", items);
        return response;
    }

    private JsonObject historyMessage(Client client, MySqlStore.ChatEntry entry) {
        JsonObject item = new JsonObject();
        item.addProperty("id", entry.id());
        item.addProperty("role", value(entry.role()));
        String content = value(entry.content());
        String messageType = value(entry.messageType()).toUpperCase(Locale.ROOT);
        if ("IMAGE".equals(messageType) || "FILE".equals(messageType)) {
            try {
                JsonObject media = JsonParser.parseString(content).getAsJsonObject();
                String caption = jsonString(media, "caption");
                item.addProperty("content", caption.isBlank()
                        ? ("IMAGE".equals(messageType) ? "图片已生成" : "文件已生成") : caption);
                String artifactId = jsonString(media, "artifactId");
                String fileName = jsonString(media, "fileName");
                String contentType = jsonString(media, "contentType");
                long size = media.has("size") ? media.get("size").getAsLong() : -1L;
                artifacts.restore(client.userId, artifactId, fileName, contentType, size)
                        .ifPresent(artifact -> {
                            item.addProperty("kind", "IMAGE".equals(messageType) ? "image" : "file");
                            item.addProperty("artifactId", artifact.id());
                            item.addProperty("fileName", artifact.fileName());
                            item.addProperty("contentType", artifact.contentType());
                            item.addProperty("size", artifact.size());
                        });
                return item;
            } catch (Exception ignored) {
                // Malformed or missing media metadata falls back to a safe text message.
            }
        }
        item.addProperty("content", safeHistoryText(content));
        return item;
    }

    private static String jsonString(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String safeHistoryText(String content) {
        if (content.startsWith("[用户发送了")) {
            int pathSeparator = content.indexOf(": ");
            if (pathSeparator > 0) return content.substring(0, pathSeparator) + "]";
        }
        return content;
    }

    private void publishTaskEvent(WebTaskRegistry.Task task, AgentEvent.Type type,
                                  String content, String state) {
        WebTaskRegistry.Snapshot snapshot = task.snapshot();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("state", state);
        metadata.put("taskState", snapshot.state());
        metadata.put("sessionId", snapshot.sessionId());
        metadata.put("requestId", snapshot.requestId());
        metadata.put("startedAt", snapshot.createdAt());
        metadata.put("elapsedMs", snapshot.elapsedMs());
        metadata.put("attempt", snapshot.attempt());
        metadata.put("detail", snapshot.detail());
        events.publish(task.userId(), new AgentEvent(type, content, metadata));
    }

    private static String webLog(String event, Client client, WebTaskRegistry.Task task) {
        return RequestLogContext.prefix(ChannelType.WEB, event, client.userId,
                task.sessionId(), task.id());
    }

    private static String describeParts(List<MessagePart> parts) {
        if (parts == null || parts.isEmpty()) return "empty";
        MessagePart first = parts.getFirst();
        if (first instanceof MessagePart.Text text) return "text chars=" + text.text().length();
        if (first instanceof MessagePart.Image image) return "image bytes=" + image.content().length;
        if (first instanceof MessagePart.File file) {
            return "file name=" + RequestLogContext.preview(file.fileName())
                    + " bytes=" + file.content().length;
        }
        return first.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private void streamEvents(HttpExchange exchange, Client client) throws IOException {
        long afterId = parseEventId(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
        if (afterId == 0L) afterId = parseEventId(query(exchange, "after"));
        boolean wechatScope = "wechat".equals(query(exchange, "scope"));
        String stream = wechatScope ? WechatWebBridge.EVENT_STREAM : client.userId;
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache, no-store");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream output = exchange.getResponseBody();
             WebEventBroker.Subscription subscription = events.subscribe(stream, afterId)) {
            output.write(": connected\n\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            while (running && !Thread.currentThread().isInterrupted()) {
                WebEventBroker.Envelope envelope = subscription.poll(15, TimeUnit.SECONDS);
                if (subscription.isClosed()) break;
                if (envelope == null) {
                    output.write(": ping\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    if (wechatScope && !isPairedWechatEvent(client, envelope.event())) continue;
                    JsonObject payload = new JsonObject();
                    payload.addProperty("type", envelope.event().type().name().toLowerCase(Locale.ROOT));
                    payload.addProperty("content", envelope.event().content());
                    payload.add("metadata", gson.toJsonTree(envelope.event().metadata()));
                    String data = "id: " + envelope.id() + "\nevent: agent\ndata: "
                            + gson.toJson(payload) + "\n\n";
                    output.write(data.getBytes(StandardCharsets.UTF_8));
                }
                output.flush();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // Browser disconnected.
        }
    }

    private boolean isPairedWechatEvent(Client client, AgentEvent event) {
        if (wechatBridge == null) return false;
        WechatWebBridge.Status status = wechatBridge.status(client.userId);
        Object eventUser = event.metadata().get("userId");
        return status.paired() && eventUser != null && status.wechatUserId().equals(String.valueOf(eventUser));
    }

    private void handleWechatApi(HttpExchange exchange, Client client, String suffix) throws IOException {
        if (wechatBridge == null) throw new HttpError(503, "微信控制台不可用");
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && (suffix.isBlank() || "/status".equals(suffix))) {
            sendJson(exchange, 200, gson.toJsonTree(wechatBridge.status(client.userId)).getAsJsonObject());
            return;
        }
        if ("POST".equals(method) && "/activate".equals(suffix)) {
            sendJson(exchange, 200, gson.toJsonTree(wechatBridge.activate(client.userId)).getAsJsonObject());
            return;
        }
        if ("POST".equals(method) && "/pair".equals(suffix)) {
            sendJson(exchange, 201, gson.toJsonTree(wechatBridge.beginPairing(client.userId)).getAsJsonObject());
            return;
        }
        if ("GET".equals(method) && "/messages".equals(suffix)) {
            JsonObject response = new JsonObject();
            response.add("messages", gson.toJsonTree(wechatBridge.messages(client.userId)));
            sendJson(exchange, 200, response);
            return;
        }
        if ("POST".equals(method) && "/messages".equals(suffix)) {
            JsonObject body = jsonBody(exchange);
            String text = jsonString(body, "text").trim();
            if (text.isBlank()) throw new HttpError(400, "消息不能为空");
            if (text.length() > MAX_TEXT_CHARS) throw new HttpError(413, "消息内容过长");
            WechatWebBridge.Status status = wechatBridge.activate(client.userId);
            if (!status.paired() || !status.connected()) throw new HttpError(409, "微信 Bot 尚未登录");
            if (!status.ready()) throw new HttpError(409, status.detail());
            String wechatUserId = status.wechatUserId();
            messageExecutor.execute(wechatUserId, () -> {
                try {
                    wechatBridge.processWebInput(client.userId, text);
                } catch (Exception error) {
                    System.err.println(RequestLogContext.prefix(ChannelType.WECHAT, "Web 同步失败",
                            wechatUserId, "", "") + " error=" + RequestLogContext.error(error));
                }
            });
            JsonObject response = new JsonObject();
            response.addProperty("accepted", true);
            response.addProperty("wechatUserId", wechatUserId);
            sendJson(exchange, 202, response);
            return;
        }
        throw new HttpError(404, "微信控制台接口不存在");
    }

    private void handleWorkspaceApi(HttpExchange exchange, Client client, String suffix) throws IOException {
        if (workspaceFiles == null) throw new HttpError(503, "文件工作区未配置");
        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "/roots".equals(suffix)) {
            JsonObject response = new JsonObject();
            response.add("roots", gson.toJsonTree(workspaceFiles.roots()));
            sendJson(exchange, 200, response);
            return;
        }
        if ("GET".equals(method) && "/list".equals(suffix)) {
            JsonObject response = new JsonObject();
            response.add("entries", gson.toJsonTree(workspaceFiles.list(query(exchange, "rootId"), query(exchange, "path"))));
            sendJson(exchange, 200, response);
            return;
        }
        if ("GET".equals(method) && "/search".equals(suffix)) {
            JsonObject response = new JsonObject();
            response.add("entries", gson.toJsonTree(workspaceFiles.search(query(exchange, "rootId"), query(exchange, "q"))));
            sendJson(exchange, 200, response);
            return;
        }
        if ("GET".equals(method) && "/preview".equals(suffix)) {
            sendJson(exchange, 200, gson.toJsonTree(workspaceFiles.preview(query(exchange, "rootId"), query(exchange, "path"))).getAsJsonObject());
            return;
        }
        if ("GET".equals(method) && "/content".equals(suffix)) {
            WorkspaceFileService.Preview preview = workspaceFiles.preview(
                    query(exchange, "rootId"), query(exchange, "path"));
            if (!preview.contentType().startsWith("image/") && !"application/pdf".equals(preview.contentType())) {
                throw new HttpError(415, "该文件类型不支持内嵌预览");
            }
            send(exchange, 200, preview.contentType(),
                    workspaceFiles.readForPreview(preview.rootId(), preview.path()));
            return;
        }
        JsonObject body = "POST".equals(method) ? jsonBody(exchange) : null;
        if ("POST".equals(method) && "/prepare-write".equals(suffix)) {
            sendJson(exchange, 200, gson.toJsonTree(workspaceFiles.prepareWrite(client.userId,
                    jsonString(body, "rootId"),
                    jsonString(body, "path"), jsonString(body, "content"))).getAsJsonObject());
            return;
        }
        if ("POST".equals(method) && "/confirm-write".equals(suffix)) {
            sendJson(exchange, 200, gson.toJsonTree(workspaceFiles.confirmWrite(client.userId,
                    jsonString(body, "token"))).getAsJsonObject());
            return;
        }
        if ("POST".equals(method) && "/prepare-send".equals(suffix)) {
            String rootId = jsonString(body, "rootId");
            String path = jsonString(body, "path");
            byte[] bytes = workspaceFiles.readForDelivery(rootId, path);
            String token = UUID.randomUUID().toString();
            pendingDeliveries.put(token, new PendingDelivery(client.userId, rootId, path));
            JsonObject response = new JsonObject();
            response.addProperty("token", token);
            response.addProperty("fileName", workspaceFiles.fileName(rootId, path));
            response.addProperty("size", bytes.length);
            sendJson(exchange, 200, response);
            return;
        }
        if ("POST".equals(method) && "/confirm-send".equals(suffix)) {
            PendingDelivery pending = pendingDeliveries.remove(jsonString(body, "token"));
            if (pending == null || !pending.webUserId().equals(client.userId)) throw new HttpError(400, "文件发送确认已过期");
            boolean sent;
            try {
                sent = wechatBridge != null && wechatBridge.sendFile(client.userId,
                        workspaceFiles.readForDelivery(pending.rootId(), pending.path()),
                        workspaceFiles.fileName(pending.rootId(), pending.path()), "来自本地工作空间");
            } catch (Exception error) {
                throw new HttpError(502, "微信文件发送失败");
            }
            if (!sent) {
                throw new HttpError(409, "微信未绑定或未连接");
            }
            JsonObject response = new JsonObject();
            response.addProperty("sent", true);
            sendJson(exchange, 200, response);
            return;
        }
        throw new HttpError(404, "文件工作区接口不存在");
    }

    private JsonObject jsonBody(HttpExchange exchange) throws IOException {
        try {
            return JsonParser.parseString(new String(readBody(exchange, 1024 * 1024L), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new HttpError(400, "请求体不是有效的 JSON");
        }
    }

    private void sendLoginStatus(HttpExchange exchange, Client client) throws IOException {
        WechatWebBridge.Status status = wechatBridge == null
                ? new WechatWebBridge.Status(false, "", false, false, "微信控制台不可用")
                : wechatBridge.activate(client.userId);
        JsonObject response = new JsonObject();
        response.addProperty("available", loginQrPage != null && !loginQrPage.latestQrDataUri().isBlank());
        if (!"1".equals(query(exchange, "summary"))) {
            response.addProperty("qrDataUri", loginQrPage == null ? "" : loginQrPage.latestQrDataUri());
        }
        response.addProperty("generatedAtMillis", loginQrPage == null ? 0L : loginQrPage.latestQrGeneratedAtMillis());
        response.addProperty("connected", status.connected());
        response.addProperty("paired", status.paired());
        response.addProperty("ready", status.ready());
        response.addProperty("detail", status.detail());
        sendJson(exchange, 200, response);
    }

    private void sendNavigation(HttpExchange exchange, Client client) throws IOException {
        WechatWebBridge.Status status = wechatBridge == null
                ? new WechatWebBridge.Status(false, "", false, false, "微信控制台不可用")
                : wechatBridge.activate(client.userId);
        if (status.paired()) {
            if (dailyDashboardServer != null) dailyDashboardServer.useUser(status.wechatUserId());
            if (sessionManagementServer != null) sessionManagementServer.useUser(status.wechatUserId());
        }
        JsonObject response = new JsonObject();
        response.addProperty("wechatConnected", status.connected());
        response.addProperty("wechatReady", status.ready());
        response.addProperty("wechatUrl", status.connected() && status.paired() ? "/web/wechat" : loginPageUrl(client));
        response.addProperty("loginUrl", loginPageUrl(client));
        response.addProperty("planUrl", dailyDashboardServer == null ? "" : dailyDashboardServer.url());
        response.addProperty("sessionsUrl", sessionManagementServer == null ? "" : sessionManagementServer.url());
        sendJson(exchange, 200, response);
    }

    private String loginPageUrl(Client client) {
        return "/web/wechat-login?clientId=" + URLEncoder.encode(client.userId.substring(4), StandardCharsets.UTF_8)
                + "&workspaceId=" + URLEncoder.encode(client.workspaceId, StandardCharsets.UTF_8);
    }

    private void sendWechatLoginPage(HttpExchange exchange) throws IOException {
        if (wechatBridge != null) {
            WechatWebBridge.Status status = wechatBridge.activate(client(exchange).userId);
            if (status.connected() && status.paired()) {
                redirect(exchange, "/web/wechat");
                return;
            }
        }
        if (loginQrPage == null) throw new HttpError(503, "微信登录页面不可用");
        String polling = """
                <script>
                (() => {
                  const check = async () => {
                    try {
                      const query = new URLSearchParams(location.search);
                      query.set('summary', '1');
                      const response = await fetch('/api/web/login?' + query.toString(), { cache: 'no-store' });
                      const status = await response.json();
                      if (status.connected && status.paired) location.replace('/web/wechat');
                      else if (status.available && document.documentElement.dataset.qrPending === 'true') location.reload();
                    } catch (_) { }
                  };
                  setInterval(check, 1500);
                  check();
                })();
                </script>
                """;
        String html = loginQrPage.currentPageHtml().replace("</body>", polling + "</body>");
        send(exchange, 200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private void sendArtifact(HttpExchange exchange, Client client, String artifactId) throws IOException {
        WebArtifactStore.Artifact artifact = artifacts.find(client.userId, artifactId)
                .orElseThrow(() -> new HttpError(404, "文件不存在"));
        String encoded = URLEncoder.encode(artifact.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = artifact.contentType().startsWith("image/") ? "inline" : "attachment";
        exchange.getResponseHeaders().set("Content-Disposition",
                disposition + "; filename=\"artifact\"; filename*=UTF-8''" + encoded);
        send(exchange, 200, artifact.contentType(), Files.readAllBytes(artifact.path()));
    }

    private Client client(HttpExchange exchange) {
        String clientId = value(exchange.getRequestHeaders().getFirst("X-Web-Client-Id"));
        if (clientId.isBlank()) clientId = query(exchange, "clientId");
        if (!clientId.matches("[a-zA-Z0-9_-]{8,80}")) throw new HttpError(400, "Web 客户端标识无效");
        String workspaceId = value(exchange.getRequestHeaders().getFirst("X-Web-Workspace-Id"));
        if (workspaceId.isBlank()) workspaceId = query(exchange, "workspaceId");
        if (workspaceId.isBlank()) workspaceId = "default";
        if (!workspaceId.matches("[a-zA-Z0-9._-]{1,80}")) throw new HttpError(400, "工作区标识无效");
        return new Client("web-" + clientId, workspaceId);
    }

    private String requireOwnedSession(Client client, String requestedSessionId) {
        String sessionId = value(requestedSessionId).trim();
        if (sessionId.isBlank()) sessionId = sessions.getCurrentSession(client.userId).sessionId();
        if (database.isAvailable()) {
            MySqlStore.SessionRow row = database.findSession(sessionId);
            if (row == null || !client.userId.equals(row.wechatId())) {
                throw new HttpError(404, "会话不存在");
            }
        } else if (!sessionId.equals(sessions.getCurrentSession(client.userId).sessionId())) {
            throw new HttpError(404, "会话不存在");
        }
        return sessionId;
    }

    private JsonObject optionalJsonBody(HttpExchange exchange, long limit) throws IOException {
        byte[] bytes = readBody(exchange, limit);
        if (bytes.length == 0) return new JsonObject();
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception error) {
            throw new HttpError(400, "请求体不是有效的 JSON");
        }
    }

    private String sessionKey(String userId, String sessionId) {
        return userId + "\u0000web-task\u0000" + sessionId;
    }

    private JsonObject sessionJson(MySqlStore.SessionRow row) {
        JsonObject item = new JsonObject();
        item.addProperty("sessionId", row.sessionId());
        item.addProperty("title", row.title() == null || row.title().isBlank()
                ? "会话 · " + format(row.createdTime()) : row.title());
        item.addProperty("titleSource", value(row.titleSource()));
        item.addProperty("status", value(row.status()));
        item.addProperty("lastActiveTime", format(row.lastActiveTime()));
        return item;
    }

    private byte[] readBody(HttpExchange exchange, long limit) throws IOException {
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                if (Long.parseLong(contentLength) > limit) throw new HttpError(413, "上传文件过大");
            } catch (NumberFormatException ignored) {
                throw new HttpError(400, "Content-Length 无效");
            }
        }
        try (InputStream input = exchange.getRequestBody()) {
            byte[] body = input.readNBytes(Math.toIntExact(Math.min(Integer.MAX_VALUE, limit + 1)));
            if (body.length > limit) throw new HttpError(413, "上传文件过大");
            return body;
        }
    }

    private boolean isImage(String contentType, String fileName) {
        if (contentType.startsWith("image/")) return true;
        String lower = fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".webp");
    }

    private String query(HttpExchange exchange, String name) {
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) return "";
        for (String pair : raw.split("&")) {
            String[] parts = pair.split("=", 2);
            if (URLDecoder.decode(parts[0], StandardCharsets.UTF_8).equals(name)) {
                return parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            }
        }
        return "";
    }

    private long parseEventId(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private String format(LocalDateTime value) {
        return value == null ? "" : SESSION_TIME.format(value);
    }

    private String value(String value) {
        return value == null ? "" : value;
    }

    private void sendResource(HttpExchange exchange, String contentType, String resource) throws IOException {
        try (InputStream input = WebChatServer.class.getResourceAsStream(resource)) {
            if (input == null) throw new HttpError(404, "页面资源不存在");
            send(exchange, 200, contentType, input.readAllBytes());
        }
    }

    private void sendJson(HttpExchange exchange, int status, JsonObject body) throws IOException {
        send(exchange, status, "application/json; charset=utf-8",
                gson.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private void sendError(HttpExchange exchange, int status, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("error", message);
        sendJson(exchange, status, body);
    }

    private void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: blob:; style-src 'self'; script-src 'self'");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override
    public void close() {
        running = false;
        if (server != null) server.stop(0);
        httpExecutor.shutdownNow();
    }

    private record Client(String userId, String workspaceId) {
    }

    private record PendingDelivery(String webUserId, String rootId, String path) {
    }

    private static final class HttpError extends RuntimeException {
        private final int status;

        private HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
