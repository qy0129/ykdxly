package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.planning.TaskPlan;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.example.ilink.platform.persistence.MySqlStore;

/** 保存每位用户的全部任务计划，并维护当前选中的计划。 */
public final class PlanSessionStore {

    private final Map<String, Map<String, TaskPlan>> plans = new ConcurrentHashMap<>();
    private final Map<String, String> activePlanIds = new ConcurrentHashMap<>();
    private final Map<String, PendingPlanRequest> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, PendingCalendarSync> pendingCalendarSyncs = new ConcurrentHashMap<>();
    private final Map<String, String> taskCalendarLinks = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedPendingUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database;
    private final boolean persistent;
    private final Gson gson = new Gson();
    private static final String PENDING_PLAN_KEY = "pending_plan_request";
    private static final String PENDING_SYNC_KEY = "pending_plan_calendar_sync";
    private static final String ACTIVE_PLAN_KEY = "active_plan_id";
    private static final String NO_ACTIVE_PLAN = "__none__";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;

    public PlanSessionStore() {
        this(true);
    }

    public PlanSessionStore(boolean persistent) {
        this.persistent = persistent;
        this.database = persistent ? MySqlStore.getInstance() : null;
    }

    /** 保存计划，并将它设为用户当前计划。 */
    public void set(String userId, TaskPlan plan) {
        loadedUsers.add(userId);
        plans.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>()).put(plan.id(), plan);
        activePlanIds.put(userId, plan.id());
        if (persistent) {
            database.saveTaskPlan(userId, plan);
            database.saveUserState(userId, ACTIVE_PLAN_KEY, plan.id());
        }
    }

    /** 获取用户当前计划；没有计划时返回 null。 */
    public TaskPlan get(String userId) {
        ensureLoaded(userId);
        Map<String, TaskPlan> userPlans = plans.get(userId);
        if (userPlans == null || userPlans.isEmpty()) return null;
        String activeId = activePlanIds.get(userId);
        if (NO_ACTIVE_PLAN.equals(activeId)) return null;
        TaskPlan active = activeId == null ? null : userPlans.get(activeId);
        if (active != null) return active;
        return userPlans.values().stream().findFirst().orElse(null);
    }

    /** 返回用户全部计划，当前计划排在最前面。 */
    public List<TaskPlan> list(String userId) {
        ensureLoaded(userId);
        String activeId = activePlanIds.getOrDefault(userId, "");
        return plans.getOrDefault(userId, Map.of()).values().stream()
                .sorted((left, right) -> {
                    if (left.id().equals(activeId)) return -1;
                    if (right.id().equals(activeId)) return 1;
                    return right.createdDate().compareTo(left.createdDate());
                })
                .toList();
    }

    public TaskPlan get(String userId, String planId) {
        ensureLoaded(userId);
        return plans.getOrDefault(userId, Map.of()).get(planId);
    }

    /** 按计划编号或目标关键字切换当前计划。 */
    public TaskPlan select(String userId, String selector) {
        ensureLoaded(userId);
        String value = selector == null ? "" : selector.trim();
        if (value.isBlank()) return null;
        TaskPlan selected = plans.getOrDefault(userId, Map.of()).values().stream()
                .filter(plan -> plan.id().equalsIgnoreCase(value) || plan.goal().contains(value))
                .findFirst().orElse(null);
        if (selected != null) {
            activePlanIds.put(userId, selected.id());
            if (persistent) database.saveUserState(userId, ACTIVE_PLAN_KEY, selected.id());
        }
        return selected;
    }

    /** 判断用户是否已经创建过计划。 */
    public boolean hasPlan(String userId) {
        return get(userId) != null;
    }

    /** 清除用户当前计划。 */
    public void clear(String userId) {
        activePlanIds.put(userId, NO_ACTIVE_PLAN);
        if (persistent) database.saveUserState(userId, ACTIVE_PLAN_KEY, NO_ACTIVE_PLAN);
    }

    /** 保存任务与同步创建的日历事件之间的关联。 */
    public void linkTaskToCalendar(String taskId, String calendarEventId) {
        taskCalendarLinks.put(taskId, calendarEventId);
        if (persistent) database.linkPlanTaskToCalendar(taskId, calendarEventId);
    }

    public String calendarEventIdForTask(String taskId) {
        String cached = taskCalendarLinks.get(taskId);
        if (cached != null) return cached;
        if (!persistent) return "";
        String stored = database.loadCalendarEventIdForTask(taskId);
        if (!stored.isBlank()) taskCalendarLinks.put(taskId, stored);
        return stored;
    }

    public void unlinkTaskFromCalendar(String taskId) {
        taskCalendarLinks.remove(taskId);
        if (persistent) database.deletePlanTaskCalendarLink(taskId);
    }

    private void ensureLoaded(String userId) {
        if (!loadedUsers.add(userId)) return;
        if (!persistent) return;
        List<TaskPlan> stored = database.loadTaskPlans(userId);
        if (!stored.isEmpty()) {
            Map<String, TaskPlan> userPlans = new ConcurrentHashMap<>();
            stored.forEach(plan -> userPlans.put(plan.id(), plan));
            plans.put(userId, userPlans);
            String selectedId = database.loadUserState(userId, ACTIVE_PLAN_KEY);
            activePlanIds.put(userId, NO_ACTIVE_PLAN.equals(selectedId) ? NO_ACTIVE_PLAN
                    : userPlans.containsKey(selectedId) ? selectedId : stored.getFirst().id());
        }
    }

    /** 保存等待用户补充截止时间的规划请求。 */
    public void setPending(String userId, PendingPlanRequest request) {
        loadedPendingUsers.add(userId);
        pendingRequests.put(userId, request);
        if (persistent) database.saveUserState(userId, PENDING_PLAN_KEY, gson.toJson(request));
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
        if (persistent) database.deleteUserState(userId, PENDING_PLAN_KEY);
    }

    /** 保存等待用户确认同步到日历的计划，避免未经确认批量创建提醒。 */
    public void setPendingCalendarSync(String userId, TaskPlan plan) {
        loadedPendingUsers.add(userId);
        PendingCalendarSync state = new PendingCalendarSync(plan, System.currentTimeMillis() + TTL_MILLIS);
        pendingCalendarSyncs.put(userId, state);
        if (persistent) database.saveUserState(userId, PENDING_SYNC_KEY, gson.toJson(state));
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
        if (persistent) database.deleteUserState(userId, PENDING_SYNC_KEY);
    }

    private void ensurePendingLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedPendingUsers.add(userId)) return;
        if (!persistent) return;
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
