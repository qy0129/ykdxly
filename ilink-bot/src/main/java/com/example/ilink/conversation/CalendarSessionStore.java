package com.example.ilink.conversation;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 保存等待用户补充具体提醒时间的日历请求。 */
public final class CalendarSessionStore {

    private final Map<String, PendingEvent> pendingEvents = new ConcurrentHashMap<>();

    public void setPending(String userId, PendingEvent event) {
        pendingEvents.put(userId, event);
    }

    public PendingEvent getPending(String userId) {
        return pendingEvents.get(userId);
    }

    public boolean hasPending(String userId) {
        return pendingEvents.containsKey(userId);
    }

    public void clearPending(String userId) {
        pendingEvents.remove(userId);
    }

    /** 已识别标题和重复规则、但尚缺时间的事件草稿。 */
    public record PendingEvent(String title, String type, String recurrence, int reminderMinutes) {
    }
}
