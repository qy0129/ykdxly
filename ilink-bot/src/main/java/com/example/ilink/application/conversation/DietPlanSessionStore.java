package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 保存已展示、等待用户确认同步到日历的饮食计划。 */
public final class DietPlanSessionStore {
    private static final String STATE_KEY = "pending_diet_plan";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    private final Map<String, DietPlanDraft> drafts = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();

    public void set(String userId, DietPlanDraft draft) {
        loadedUsers.add(userId);
        drafts.put(userId, draft);
        database.saveUserState(userId, STATE_KEY, gson.toJson(draft));
    }

    public DietPlanDraft get(String userId) {
        ensureLoaded(userId);
        return drafts.get(userId);
    }

    public boolean has(String userId) { return get(userId) != null; }

    public void clear(String userId) {
        drafts.remove(userId);
        database.deleteUserState(userId, STATE_KEY);
    }

    private void ensureLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedUsers.add(userId)) return;
        String value = database.loadUserState(userId, STATE_KEY);
        if (value.isBlank()) return;
        try {
            DietPlanDraft state = gson.fromJson(value, DietPlanDraft.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) drafts.put(userId, state);
            else database.deleteUserState(userId, STATE_KEY);
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, STATE_KEY);
        }
    }

    /** 当前阶段决定下一条用户消息应作为偏好信息还是日历确认。 */
    public record DietPlanDraft(String goal, String stage, long expiresAtMillis) {
        public DietPlanDraft(String goal, String stage) {
            this(goal, stage, System.currentTimeMillis() + TTL_MILLIS);
        }

        public DietPlanDraft withStage(String value) { return new DietPlanDraft(goal, value); }
    }
}
