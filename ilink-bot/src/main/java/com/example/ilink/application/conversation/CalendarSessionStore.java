package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.calendar.CalendarDraft;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 保存等待用户补充具体提醒时间的日历请求。 */
public final class CalendarSessionStore {

    private static final String EVENT_KEY = "pending_calendar_event";
    private static final String OPERATION_KEY = "pending_calendar_operation";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private final Map<String, PendingEvent> pendingEvents = new ConcurrentHashMap<>();
    private final Map<String, PendingOperation> pendingOperations = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();

    public void setPending(String userId, PendingEvent event) {
        loadedUsers.add(userId);
        pendingEvents.put(userId, event);
        database.saveUserState(userId, EVENT_KEY, gson.toJson(event));
    }

    public PendingEvent getPending(String userId) {
        ensureLoaded(userId);
        return pendingEvents.get(userId);
    }

    public boolean hasPending(String userId) {
        ensureLoaded(userId);
        return pendingEvents.containsKey(userId) || pendingOperations.containsKey(userId);
    }

    public void clearPending(String userId) {
        pendingEvents.remove(userId);
        database.deleteUserState(userId, EVENT_KEY);
    }

    public void setPendingOperation(String userId, PendingOperation operation) {
        loadedUsers.add(userId);
        pendingOperations.put(userId, operation);
        database.saveUserState(userId, OPERATION_KEY, gson.toJson(operation));
    }

    public PendingOperation getPendingOperation(String userId) {
        ensureLoaded(userId);
        return pendingOperations.get(userId);
    }

    public void clearPendingOperation(String userId) {
        pendingOperations.remove(userId);
        database.deleteUserState(userId, OPERATION_KEY);
    }

    private void ensureLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedUsers.add(userId)) return;
        load(userId, EVENT_KEY, PendingEvent.class, pendingEvents);
        load(userId, OPERATION_KEY, PendingOperation.class, pendingOperations);
    }

    private <T extends ExpiringState> void load(String userId, String key, Class<T> type, Map<String, T> target) {
        String value = database.loadUserState(userId, key);
        if (value.isBlank()) return;
        try {
            T state = gson.fromJson(value, type);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) target.put(userId, state);
            else database.deleteUserState(userId, key);
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, key);
        }
    }

    /** 已识别标题和重复规则、但尚缺时间的事件草稿。 */
    private interface ExpiringState {
        long expiresAtMillis();
    }

    public record PendingEvent(CalendarDraft draft, long expiresAtMillis) implements ExpiringState {
        public PendingEvent(CalendarDraft draft) {
            this(draft, System.currentTimeMillis() + TTL_MILLIS);
        }
    }

    public record PendingOperation(String action, java.util.List<String> eventIds,
                                   int postponeMinutes, long expiresAtMillis) implements ExpiringState {
        public PendingOperation(String action, java.util.List<String> eventIds, int postponeMinutes) {
            this(action, java.util.List.copyOf(eventIds), postponeMinutes,
                    System.currentTimeMillis() + TTL_MILLIS);
        }
    }
}
