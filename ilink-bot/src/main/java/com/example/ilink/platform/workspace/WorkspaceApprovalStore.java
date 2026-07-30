package com.example.ilink.platform.workspace;

import com.example.ilink.application.tooling.ToolContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived user-scoped approvals for workspace writes and file delivery. */
public final class WorkspaceApprovalStore {
    private static final long TTL_MILLIS = 5 * 60_000L;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public void prepareWrite(ToolContext context, String rootId, String path, String token, String summary) {
        put(context, new Pending(Action.WRITE, rootId, path, token, summary,
                System.currentTimeMillis() + TTL_MILLIS));
    }

    public void prepareSend(ToolContext context, String rootId, String path, String summary) {
        put(context, new Pending(Action.SEND, rootId, path, "", summary,
                System.currentTimeMillis() + TTL_MILLIS));
    }

    public Pending consume(String userId, String sessionId, Action expected) {
        String key = scope(userId, sessionId);
        Pending value = pending.get(key);
        if (value == null) return null;
        if (value.expiresAtMillis() < System.currentTimeMillis()) {
            pending.remove(key, value);
            return null;
        }
        if (value.action() != expected || !pending.remove(key, value)) return null;
        return value;
    }

    public boolean cancel(String userId, String sessionId) {
        return pending.remove(scope(userId, sessionId)) != null;
    }

    public static String scope(ToolContext context) {
        return scope(context.userId(), context.sessionId());
    }

    public static String scope(String userId, String sessionId) {
        return userId + "|" + (sessionId == null ? "" : sessionId);
    }

    private void put(ToolContext context, Pending value) {
        pending.put(scope(context), value);
    }

    public enum Action { WRITE, SEND }

    public record Pending(Action action, String rootId, String path, String token,
                          String summary, long expiresAtMillis) { }
}
