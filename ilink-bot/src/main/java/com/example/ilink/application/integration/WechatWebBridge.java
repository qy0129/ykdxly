package com.example.ilink.application.integration;

import com.example.ilink.adapter.outbound.web.WebEventBroker;
import com.example.ilink.adapter.outbound.web.WebArtifactStore;
import com.example.ilink.application.messaging.AgentEvent;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.messaging.RequestLogContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit local pairing between one Web client and the currently active WeChat Bot user. */
public final class WechatWebBridge {

    public static final String EVENT_STREAM = "web-wechat-bridge";
    private static final long PAIRING_TTL_MILLIS = 5 * 60_000L;
    private static final int HISTORY_LIMIT = 160;

    private final WebEventBroker events;
    private final WebArtifactStore artifacts;
    private final UserSessionStore sessions;
    private final Map<String, PendingPairing> pending = new ConcurrentHashMap<>();
    private final Map<String, String> webToWechat = new ConcurrentHashMap<>();
    private final Map<String, String> wechatToWeb = new ConcurrentHashMap<>();
    private final Map<String, Deque<Message>> histories = new ConcurrentHashMap<>();
    private final Map<String, Boolean> syncReplies = new ConcurrentHashMap<>();
    private volatile Gateway gateway;
    private volatile String activeWechatUser = "";

    public WechatWebBridge(WebEventBroker events) {
        this(events, null, null);
    }

    public WechatWebBridge(WebEventBroker events, WebArtifactStore artifacts) {
        this(events, artifacts, null);
    }

    public WechatWebBridge(WebEventBroker events, WebArtifactStore artifacts,
                           UserSessionStore sessions) {
        this.events = events;
        this.artifacts = artifacts;
        this.sessions = sessions;
    }

    public void attach(Gateway gateway) {
        this.gateway = gateway;
    }

    public Pairing beginPairing(String webUserId) {
        String code = String.valueOf(100000 + (int) (Math.random() * 900000));
        long expiresAt = System.currentTimeMillis() + PAIRING_TTL_MILLIS;
        pending.put(code, new PendingPairing(webUserId, expiresAt));
        return new Pairing(code, expiresAt);
    }

    /** Consumes a phone-side "绑定 123456" message before it enters normal Bot routing. */
    public boolean consumePairing(String wechatUserId, String text) {
        String value = text == null ? "" : text.trim();
        if (!value.matches("(?:绑定|配对)\\s*\\d{6}")) return false;
        String code = value.replaceAll("\\D", "");
        PendingPairing pairing = pending.remove(code);
        if (pairing == null || pairing.expiresAtMillis() < System.currentTimeMillis()) return false;
        bind(pairing.webUserId(), wechatUserId);
        activeWechatUser = wechatUserId;
        publish("paired", "微信已绑定本地控制台", wechatUserId, "system", "sent");
        return true;
    }

    public void updateActiveUser(String wechatUserId) {
        if (wechatUserId != null && !wechatUserId.isBlank()) {
            activeWechatUser = wechatUserId;
        }
    }

    /** Current conversation shared by phone WeChat and the Web workbench. */
    public String sessionIdFor(String wechatUserId) {
        if (wechatUserId == null || wechatUserId.isBlank()) return "";
        return sessions == null ? "" : sessions.getCurrentSession(wechatUserId).sessionId();
    }

    public Status status(String webUserId) {
        String userId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        boolean paired = !userId.isBlank();
        boolean connected = current != null && current.connected();
        boolean ready = paired && connected && current.canSend(userId);
        String detail = current == null ? "未连接微信客户端"
                : !connected ? current.status()
                : !paired ? "微信已登录，正在关联 Web 工作台"
                : !ready ? "微信已登录，请先在手机向 Bot 发送一条消息"
                : current.status();
        return new Status(paired, userId, connected, ready, detail);
    }

    /** Bind a loopback Web client to the single active Bot conversation after resume/login. */
    public Status activate(String webUserId) {
        Gateway current = gateway;
        if (current != null && current.connected() && !activeWechatUser.isBlank()) {
            bind(webUserId, activeWechatUser);
        }
        return status(webUserId);
    }

    public String pairedWechatUserId(String webUserId) {
        return webToWechat.getOrDefault(webUserId, "");
    }

    public boolean processWebInput(String webUserId, String text) throws Exception {
        String wechatUserId = pairedWechatUserId(webUserId);
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()
                || !current.canSend(wechatUserId)) return false;
        recordText(wechatUserId, "web_input", text, "local");
        try {
            current.mirrorWebInput(wechatUserId, text);
        } catch (Exception error) {
            publish("sync_failed", "Web 输入镜像到微信失败，AI 仍会继续处理",
                    wechatUserId, "web_input", "failed");
        }
        current.processText(wechatUserId, sessionIdFor(wechatUserId), text);
        return true;
    }

    public List<Message> messages(String webUserId) {
        String userId = webToWechat.getOrDefault(webUserId, "");
        if (userId.isBlank()) return List.of();
        Deque<Message> history = histories.get(userId);
        if (history == null) return List.of();
        synchronized (history) { return List.copyOf(history); }
    }

    public boolean syncWebInput(String webUserId, String text, boolean includeReplies) throws Exception {
        return syncWebInput(webUserId, "", text, includeReplies);
    }

    public boolean syncWebInput(String webUserId, String requestId, String text,
                                boolean includeReplies) throws Exception {
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()
                || !current.canSend(wechatUserId)) return false;
        String key = syncKey(webUserId, requestId);
        syncReplies.put(key, includeReplies);
        try {
            current.sendText(wechatUserId, text);
            return true;
        } catch (Exception error) {
            syncReplies.remove(key);
            publish("sync_failed", "Web 消息同步到微信失败，请检查连接后重试",
                    wechatUserId, "web_input", "failed");
            return false;
        }
    }

    public boolean sendFile(String webUserId, byte[] content, String fileName, String caption) throws Exception {
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()
                || !current.canSend(wechatUserId)) return false;
        current.sendFile(wechatUserId, content, fileName, caption);
        return true;
    }

    public void recordIncoming(String wechatUserId, String content) {
        updateActiveUser(wechatUserId);
        recordText(wechatUserId, "wechat_input", content, "local");
    }

    public void recordOutgoing(String wechatUserId, String content, String kind) {
        recordText(wechatUserId, kind == null ? "bot_reply" : kind, content, "sent");
    }

    public void recordIncomingImage(String wechatUserId, byte[] content, String fileName, String caption) {
        recordMedia(wechatUserId, "wechat_input", "image", content, fileName, caption, "local");
    }

    public void recordIncomingFile(String wechatUserId, byte[] content, String fileName, String caption) {
        recordMedia(wechatUserId, "wechat_input", mediaKind(fileName), content, fileName, caption, "local");
    }

    public void recordIncomingVoice(String wechatUserId, byte[] content, String transcript) {
        recordMedia(wechatUserId, "wechat_input", "audio", content, "wechat-voice.silk", transcript, "local");
    }

    public void recordIncomingVideo(String wechatUserId, byte[] content, String fileName) {
        recordMedia(wechatUserId, "wechat_input", "video", content, fileName, "", "local");
    }

    public void recordOutgoingImage(String wechatUserId, byte[] content, String fileName, String caption) {
        recordMedia(wechatUserId, "bot_reply", "image", content, fileName, caption, "sent");
    }

    public void recordOutgoingFile(String wechatUserId, byte[] content, String fileName, String caption) {
        recordMedia(wechatUserId, "bot_reply", mediaKind(fileName), content, fileName, caption, "sent");
    }

    public void mirrorWebReply(String webUserId, String reply) {
        mirrorWebReply(webUserId, "", reply);
    }

    public void mirrorWebReply(String webUserId, String requestId, String reply) {
        if (!Boolean.TRUE.equals(syncReplies.get(syncKey(webUserId, requestId)))) return;
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()
                || !current.canSend(wechatUserId)) return;
        try {
            current.sendText(wechatUserId, reply);
        } catch (Exception ignored) {
            publish("sync_failed", "Bot 回复同步到微信失败，请检查连接后重试",
                    wechatUserId, "bot_reply", "failed");
        }
    }

    public void endWebRequest(String webUserId, String requestId) {
        syncReplies.remove(syncKey(webUserId, requestId));
    }

    private static String syncKey(String webUserId, String requestId) {
        return webUserId + "|" + (requestId == null ? "" : requestId);
    }

    private void bind(String webUserId, String wechatUserId) {
        String previousWechat = webToWechat.put(webUserId, wechatUserId);
        if (previousWechat != null && !previousWechat.equals(wechatUserId)) {
            wechatToWeb.remove(previousWechat, webUserId);
        }
        wechatToWeb.put(wechatUserId, webUserId);
    }

    private void recordText(String wechatUserId, String source, String content, String syncState) {
        record(wechatUserId, source, content, syncState, "text", null);
    }

    private void recordMedia(String wechatUserId, String source, String kind, byte[] content,
                             String fileName, String caption, String syncState) {
        String webUserId = wechatToWeb.getOrDefault(wechatUserId, "");
        if (artifacts == null || webUserId.isBlank()) {
            recordText(wechatUserId, source, caption == null || caption.isBlank() ? fileName : caption, syncState);
            return;
        }
        try {
            String contentType = contentTypeFor(kind, fileName);
            WebArtifactStore.Artifact artifact = artifacts.save(webUserId, content, fileName, contentType);
            String display = caption == null || caption.isBlank()
                    ? ("image".equals(kind) ? "图片" : artifact.fileName()) : caption;
            record(wechatUserId, source, display, syncState, kind, artifact);
        } catch (Exception error) {
            System.err.println("[微信工作台] 镜像媒体保存失败: " + error.getMessage());
            recordText(wechatUserId, source, caption == null || caption.isBlank() ? fileName : caption, syncState);
        }
    }

    private void record(String wechatUserId, String source, String content, String syncState,
                        String kind, WebArtifactStore.Artifact artifact) {
        if (wechatUserId == null || wechatUserId.isBlank()) return;
        Deque<Message> history = histories.computeIfAbsent(wechatUserId, ignored -> new ArrayDeque<>());
        Message message = new Message(UUID.randomUUID().toString(), wechatUserId, source,
                content == null ? "" : content, syncState, System.currentTimeMillis(), kind,
                artifact == null ? "" : artifact.id(), artifact == null ? "" : artifact.fileName(),
                artifact == null ? "" : artifact.contentType(), artifact == null ? -1L : artifact.size());
        synchronized (history) {
            history.addLast(message);
            while (history.size() > HISTORY_LIMIT) history.removeFirst();
        }
        Map<String, Object> metadata = new java.util.LinkedHashMap<>();
        metadata.put("integrationType", "message");
        metadata.put("channel", "WECHAT");
        metadata.put("userId", wechatUserId);
        metadata.put("source", source);
        metadata.put("syncState", syncState);
        metadata.put("messageId", message.messageId());
        metadata.put("createdAtMillis", message.createdAtMillis());
        metadata.put("kind", message.kind());
        String sessionId = requestSessionId(wechatUserId);
        if (!sessionId.isBlank()) metadata.put("sessionId", sessionId);
        if (!message.artifactId().isBlank()) {
            metadata.put("artifactId", message.artifactId());
            metadata.put("fileName", message.fileName());
            metadata.put("contentType", message.contentType());
            metadata.put("size", message.size());
        }
        events.publish(EVENT_STREAM, new AgentEvent(AgentEvent.Type.COMPLETED, message.content(), metadata));
    }

    private String imageContentType(String fileName) {
        String value = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if (value.endsWith(".webp")) return "image/webp";
        if (value.endsWith(".gif")) return "image/gif";
        return "image/png";
    }

    private String contentTypeFor(String kind, String fileName) {
        if ("image".equals(kind)) return imageContentType(fileName);
        String value = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if ("audio".equals(kind)) {
            if (value.endsWith(".mp3")) return "audio/mpeg";
            if (value.endsWith(".wav")) return "audio/wav";
            if (value.endsWith(".ogg")) return "audio/ogg";
            if (value.endsWith(".m4a")) return "audio/mp4";
            if (value.endsWith(".aac")) return "audio/aac";
            if (value.endsWith(".silk")) return "audio/x-silk";
        }
        if ("video".equals(kind)) {
            if (value.endsWith(".mp4")) return "video/mp4";
            if (value.endsWith(".webm")) return "video/webm";
            if (value.endsWith(".mov")) return "video/quicktime";
        }
        return "";
    }

    private String mediaKind(String fileName) {
        String value = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (value.endsWith(".mp3") || value.endsWith(".wav") || value.endsWith(".ogg")
                || value.endsWith(".m4a") || value.endsWith(".aac") || value.endsWith(".silk")) return "audio";
        if (value.endsWith(".mp4") || value.endsWith(".webm") || value.endsWith(".mov")) return "video";
        return "file";
    }

    private void publish(String type, String content, String userId, String source, String syncState) {
        events.publish(EVENT_STREAM, new AgentEvent(AgentEvent.Type.COMPLETED, content, Map.of(
                "integrationType", type, "channel", "WECHAT", "userId", userId,
                "source", source, "syncState", syncState)));
    }

    private String requestSessionId(String wechatUserId) {
        String requestSessionId = RequestLogContext.sessionId();
        return requestSessionId.isBlank() ? sessionIdFor(wechatUserId) : requestSessionId;
    }

    public interface Gateway {
        boolean connected();
        boolean canSend(String userId);
        String status();
        void sendText(String userId, String text) throws Exception;
        void sendFile(String userId, byte[] content, String fileName, String caption) throws Exception;
        void mirrorWebInput(String userId, String text) throws Exception;
        void processText(String userId, String text) throws Exception;
        default void processText(String userId, String sessionId, String text) throws Exception {
            processText(userId, text);
        }
    }

    public record Pairing(String code, long expiresAtMillis) { }
    public record Status(boolean paired, String wechatUserId, boolean connected, boolean ready, String detail) { }
    public record Message(String messageId, String userId, String source, String content,
                          String syncState, long createdAtMillis, String kind, String artifactId,
                          String fileName, String contentType, long size) { }
    private record PendingPairing(String webUserId, long expiresAtMillis) { }
}
