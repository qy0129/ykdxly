package com.example.ilink.conversation;

import com.example.ilink.model.TaskPlan;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.ilink.storage.MySqlStore;

/** 保存每位用户当前正在执行的任务计划。 */
public final class PlanSessionStore {

    private final Map<String, TaskPlan> plans = new ConcurrentHashMap<>();
    private final Map<String, PendingPlanRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, PendingCalendarSync> pendingCalendarSyncs = new ConcurrentHashMap<>();
    private final Map<String, String> taskCalendarLinks = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedPendingUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private static final String PENDING_PLAN_KEY = "pending_plan_request";
    private static final String PENDING_SYNC_KEY = "pending_plan_calendar_sync";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

    /** 保存或替换用户当前计划。 */
    public void set(String userId, TaskPlan plan) {
        loadedUsers.add(userId);
        plans.put(userId, plan);
        database.saveTaskPlan(userId, plan);
    }

    /** 获取用户当前计划；没有计划时返回 null。 */
    public TaskPlan get(String userId) {
        ensureLoaded(userId);
        return plans.get(userId);
    }

    /** 判断用户是否已经创建过计划。 */
    public boolean hasPlan(String userId) {
        return get(userId) != null;
    }

    /** 清除用户当前计划。 */
    public void clear(String userId) {
        plans.remove(userId);
    }

    /** 保存任务与同步创建的日历事件之间的关联。 */
    public void linkTaskToCalendar(String taskId, String calendarEventId) {
        taskCalendarLinks.put(taskId, calendarEventId);
        database.linkPlanTaskToCalendar(taskId, calendarEventId);
    }

    public String calendarEventIdForTask(String taskId) {
        String cached = taskCalendarLinks.get(taskId);
        if (cached != null) return cached;
        String stored = database.loadCalendarEventIdForTask(taskId);
        if (!stored.isBlank()) taskCalendarLinks.put(taskId, stored);
        return stored;
    }

    public void unlinkTaskFromCalendar(String taskId) {
        taskCalendarLinks.remove(taskId);
        database.deletePlanTaskCalendarLink(taskId);
    }

    private void ensureLoaded(String userId) {
        if (!loadedUsers.add(userId)) return;
        TaskPlan stored = database.loadCurrentTaskPlan(userId);
        if (stored != null) plans.put(userId, stored);
    }

    /** 保存等待用户补充截止时间的规划请求。 */
    public void setPending(String userId, PendingPlanRequest request) {
        loadedPendingUsers.add(userId);
        pendingRequests.put(userId, request);
        database.saveUserState(userId, PENDING_PLAN_KEY, gson.toJson(request));
    }

    /** 获取等待补充截止时间的规划请求。 */
    public PendingPlanRequest getPending(String userId) {
        ensurePendingLoaded(userId);
        return pendingRequests.get(userId);
    }

    /** 判断用户是否正在补充规划信息。 */
    public boolean hasPending(String userId) {
        return getPending(userId) != null;
    }

    /** 清除等待补充信息的规划请求。 */
    public void clearPending(String userId) {
        pendingRequests.remove(userId);
        database.deleteUserState(userId, PENDING_PLAN_KEY);
    }

    /** 保存等待用户确认同步到日历的计划，避免未经确认批量创建提醒。 */
    public void setPendingCalendarSync(String userId, TaskPlan plan) {
        loadedPendingUsers.add(userId);
        PendingCalendarSync state = new PendingCalendarSync(plan, System.currentTimeMillis() + TTL_MILLIS);
        pendingCalendarSyncs.put(userId, state);
        database.saveUserState(userId, PENDING_SYNC_KEY, gson.toJson(state));
    }

    public TaskPlan getPendingCalendarSync(String userId) {
        ensurePendingLoaded(userId);
        PendingCalendarSync state = pendingCalendarSyncs.get(userId);
        return state == null ? null : state.plan();
    }

    public boolean hasPendingCalendarSync(String userId) {
        return getPendingCalendarSync(userId) != null;
    }

    public void clearPendingCalendarSync(String userId) {
        pendingCalendarSyncs.remove(userId);
        database.deleteUserState(userId, PENDING_SYNC_KEY);
    }

    private void ensurePendingLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedPendingUsers.add(userId)) return;
        loadPendingRequest(userId);
        loadPendingSync(userId);
    }

    private void loadPendingRequest(String userId) {
        String json = database.loadUserState(userId, PENDING_PLAN_KEY);
        if (json.isBlank()) return;
        try {
            PendingPlanRequest state = gson.fromJson(json, PendingPlanRequest.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) pendingRequests.put(userId, state);
            else database.deleteUserState(userId, PENDING_PLAN_KEY);
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_PLAN_KEY);
        }
    }

    private void loadPendingSync(String userId) {
        String json = database.loadUserState(userId, PENDING_SYNC_KEY);
        if (json.isBlank()) return;
        try {
            PendingCalendarSync state = gson.fromJson(json, PendingCalendarSync.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) pendingCalendarSyncs.put(userId, state);
            else database.deleteUserState(userId, PENDING_SYNC_KEY);
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_SYNC_KEY);
        }
    }

    /** 尚未完成的规划请求及其回复选项。 */
    public record PendingPlanRequest(
            String goal,
            String availableTime,
            String replyMode,
            String voiceStyle,
            String outputFileType,
            long expiresAtMillis) {
        public PendingPlanRequest(String goal, String availableTime, String replyMode,
                                  String voiceStyle, String outputFileType) {
            this(goal, availableTime, replyMode, voiceStyle, outputFileType,
                    System.currentTimeMillis() + TTL_MILLIS);
        }
    }

    private record PendingCalendarSync(TaskPlan plan, long expiresAtMillis) { }
}
