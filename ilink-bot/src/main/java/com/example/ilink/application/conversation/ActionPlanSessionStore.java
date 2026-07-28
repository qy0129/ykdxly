package com.example.ilink.application.conversation;

import com.example.ilink.application.routing.IntentAction;
import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 保存尚未执行完的多动作请求，允许服务重启后继续。 */
public final class ActionPlanSessionStore {

    private static final String STATE_KEY = "pending_action_plan";
    private static final long TTL_MILLIS = 24L * 60 * 60 * 1000;
    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private final Map<String, ActionPlanState> states = new ConcurrentHashMap<>();
    private final Set<String> loadedUsers = ConcurrentHashMap.newKeySet();

    public ActionPlanState get(String userId) {
        ensureLoaded(userId);
        return states.get(userId);
    }

    public boolean hasFailedAction(String userId) {
        ActionPlanState state = get(userId);
        return state != null && state.failedAction() != null;
    }

    public void save(String userId, List<IntentAction> remainingActions, IntentAction failedAction) {
        if (remainingActions.isEmpty() && failedAction == null) {
            clear(userId);
            return;
        }
        loadedUsers.add(userId);
        ActionPlanState state = new ActionPlanState(List.copyOf(remainingActions), failedAction,
                System.currentTimeMillis() + TTL_MILLIS);
        states.put(userId, state);
        database.saveUserState(userId, STATE_KEY, gson.toJson(state));
    }

    public void clear(String userId) {
        loadedUsers.add(userId);
        states.remove(userId);
        database.deleteUserState(userId, STATE_KEY);
    }

    private void ensureLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedUsers.add(userId)) return;
        String value = database.loadUserState(userId, STATE_KEY);
        if (value.isBlank()) return;
        try {
            ActionPlanState state = gson.fromJson(value, ActionPlanState.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) {
                states.put(userId, state);
            } else {
                database.deleteUserState(userId, STATE_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, STATE_KEY);
        }
    }

    public record ActionPlanState(List<IntentAction> remainingActions, IntentAction failedAction,
                                  long expiresAtMillis) {
        public ActionPlanState {
            remainingActions = remainingActions == null ? List.of() : List.copyOf(remainingActions);
        }
    }
}
