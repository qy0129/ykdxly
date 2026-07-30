package com.example.ilink.application.integration;

import com.example.ilink.adapter.outbound.web.WebEventBroker;
import com.example.ilink.application.messaging.AgentEvent;

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
    private final Map<String, PendingPairing> pending = new ConcurrentHashMap<>();
    private final Map<String, String> webToWechat = new ConcurrentHashMap<>();
    private final Map<String, Deque<Message>> histories = new ConcurrentHashMap<>();
    private final Map<String, Boolean> syncReplies = new ConcurrentHashMap<>();
    private volatile Gateway gateway;
    private volatile String activeWechatUser = "";

    public WechatWebBridge(WebEventBroker events) {
        this.events = events;
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
        webToWechat.put(pairing.webUserId(), wechatUserId);
        activeWechatUser = wechatUserId;
        publish("paired", "微信已绑定本地控制台", wechatUserId, "system", "sent");
        return true;
    }

    public void updateActiveUser(String wechatUserId) {
        if (wechatUserId != null && !wechatUserId.isBlank()) activeWechatUser = wechatUserId;
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
            webToWechat.put(webUserId, activeWechatUser);
        }
        return status(webUserId);
    }

    public String pairedWechatUserId(String webUserId) {
        return webToWechat.getOrDefault(webUserId, "");
    }

    public boolean processWebInput(String webUserId, String text) throws Exception {
        String wechatUserId = pairedWechatUserId(webUserId);
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()) return false;
        record(wechatUserId, "web_input", text, "local");
        current.mirrorWebInput(wechatUserId, text);
        current.processText(wechatUserId, text);
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
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()) return false;
        syncReplies.put(webUserId, includeReplies);
        current.sendText(wechatUserId, text);
        return true;
    }

    public boolean sendFile(String webUserId, byte[] content, String fileName, String caption) throws Exception {
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()) return false;
        current.sendFile(wechatUserId, content, fileName, caption);
        return true;
    }

    public void recordIncoming(String wechatUserId, String content) {
        updateActiveUser(wechatUserId);
        record(wechatUserId, "wechat_input", content, "local");
    }

    public void recordOutgoing(String wechatUserId, String content, String kind) {
        record(wechatUserId, kind == null ? "bot_reply" : kind, content, "sent");
    }

    public void mirrorWebReply(String webUserId, String reply) {
        if (!Boolean.TRUE.equals(syncReplies.get(webUserId))) return;
        String wechatUserId = webToWechat.getOrDefault(webUserId, "");
        Gateway current = gateway;
        if (wechatUserId.isBlank() || current == null || !current.connected()) return;
        try {
            current.sendText(wechatUserId, reply);
        } catch (Exception ignored) {
            publish("sync_failed", "同步微信失败", "", "bot_reply", "failed");
        }
    }

    private void record(String wechatUserId, String source, String content, String syncState) {
        if (wechatUserId == null || wechatUserId.isBlank()) return;
        Deque<Message> history = histories.computeIfAbsent(wechatUserId, ignored -> new ArrayDeque<>());
        Message message = new Message(UUID.randomUUID().toString(), wechatUserId, source,
                content == null ? "" : content, syncState, System.currentTimeMillis());
        synchronized (history) {
            history.addLast(message);
            while (history.size() > HISTORY_LIMIT) history.removeFirst();
        }
        events.publish(EVENT_STREAM, new AgentEvent(AgentEvent.Type.COMPLETED, message.content(), Map.of(
                "integrationType", "message", "channel", "WECHAT", "userId", wechatUserId,
                "source", source, "syncState", syncState, "messageId", message.messageId(),
                "createdAtMillis", message.createdAtMillis())));
    }

    private void publish(String type, String content, String userId, String source, String syncState) {
        events.publish(EVENT_STREAM, new AgentEvent(AgentEvent.Type.COMPLETED, content, Map.of(
                "integrationType", type, "channel", "WECHAT", "userId", userId,
                "source", source, "syncState", syncState)));
    }

    public interface Gateway {
        boolean connected();
        boolean canSend(String userId);
        String status();
        void sendText(String userId, String text) throws Exception;
        void sendFile(String userId, byte[] content, String fileName, String caption) throws Exception;
        void mirrorWebInput(String userId, String text) throws Exception;
        void processText(String userId, String text) throws Exception;
    }

    public record Pairing(String code, long expiresAtMillis) { }
    public record Status(boolean paired, String wechatUserId, boolean connected, boolean ready, String detail) { }
    public record Message(String messageId, String userId, String source, String content,
                          String syncState, long createdAtMillis) { }
    private record PendingPairing(String webUserId, long expiresAtMillis) { }
}
