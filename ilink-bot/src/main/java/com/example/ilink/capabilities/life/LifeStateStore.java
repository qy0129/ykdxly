package com.example.ilink.capabilities.life;

import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Life Agent 的轻量状态存储，数据库不可用时自动保留在内存。 */
public final class LifeStateStore {

    private static final String STATE_KEY = "life_agent_state";
    private static final int MAX_ACTIVITIES = 1000;
    private static final int MAX_REFLECTIONS = 90;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final MySqlStore database;
    private final Gson gson = new Gson();
    private final boolean persistent;

    public LifeStateStore() {
        this(true);
    }

    public LifeStateStore(boolean persistent) {
        this.persistent = persistent;
        this.database = persistent ? MySqlStore.getInstance() : null;
    }

    public synchronized StudyPlanDraft draft(String userId) {
        return state(userId).draft();
    }

    public synchronized void saveDraft(String userId, StudyPlanDraft draft) {
        State current = state(userId);
        save(userId, new State(draft, current.profiles(), current.activities(),
                current.reflections(), current.reflectionEventId()));
    }

    public synchronized void clearDraft(String userId) {
        State current = state(userId);
        save(userId, new State(null, current.profiles(), current.activities(),
                current.reflections(), current.reflectionEventId()));
    }

    public synchronized void saveProfile(String userId, StudyPlanProfile profile) {
        State current = state(userId);
        Map<String, StudyPlanProfile> profiles = new LinkedHashMap<>(current.profiles());
        profiles.put(profile.planId(), profile);
        save(userId, new State(current.draft(), profiles, current.activities(),
                current.reflections(), current.reflectionEventId()));
    }

    public synchronized StudyPlanProfile profile(String userId, String planId) {
        return state(userId).profiles().get(planId);
    }

    public synchronized List<StudyPlanProfile> profiles(String userId) {
        return List.copyOf(state(userId).profiles().values());
    }

    public synchronized void addActivity(String userId, TaskActivity activity) {
        State current = state(userId);
        List<TaskActivity> activities = new ArrayList<>(current.activities());
        activities.add(activity);
        if (activities.size() > MAX_ACTIVITIES) activities = activities.subList(activities.size() - MAX_ACTIVITIES, activities.size());
        save(userId, new State(current.draft(), current.profiles(), List.copyOf(activities),
                current.reflections(), current.reflectionEventId()));
    }

    public synchronized List<TaskActivity> activities(String userId) {
        return List.copyOf(state(userId).activities());
    }

    public synchronized void saveReflection(String userId, DailyReflection reflection) {
        State current = state(userId);
        List<DailyReflection> reflections = new ArrayList<>(current.reflections());
        reflections.removeIf(item -> item.date().equals(reflection.date()));
        reflections.add(reflection);
        if (reflections.size() > MAX_REFLECTIONS) reflections = reflections.subList(reflections.size() - MAX_REFLECTIONS, reflections.size());
        save(userId, new State(current.draft(), current.profiles(), current.activities(),
                List.copyOf(reflections), current.reflectionEventId()));
    }

    public synchronized List<DailyReflection> reflections(String userId) {
        return List.copyOf(state(userId).reflections());
    }

    public synchronized String reflectionEventId(String userId) {
        return state(userId).reflectionEventId();
    }

    public synchronized void setReflectionEventId(String userId, String eventId) {
        State current = state(userId);
        save(userId, new State(current.draft(), current.profiles(), current.activities(),
                current.reflections(), eventId == null ? "" : eventId));
    }

    private State state(String userId) {
        return states.computeIfAbsent(userId, this::load);
    }

    private State load(String userId) {
        if (!persistent) return State.empty();
        String json = database.loadUserState(userId, STATE_KEY);
        if (json.isBlank()) return State.empty();
        try {
            State value = gson.fromJson(json, State.class);
            return value == null ? State.empty() : value.normalized();
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, STATE_KEY);
            return State.empty();
        }
    }

    private void save(String userId, State state) {
        State normalized = state.normalized();
        states.put(userId, normalized);
        if (persistent) database.saveUserState(userId, STATE_KEY, gson.toJson(normalized));
    }

    private record State(
            StudyPlanDraft draft,
            Map<String, StudyPlanProfile> profiles,
            List<TaskActivity> activities,
            List<DailyReflection> reflections,
            String reflectionEventId) {

        static State empty() {
            return new State(null, Map.of(), List.of(), List.of(), "");
        }

        State normalized() {
            return new State(draft,
                    profiles == null ? Map.of() : Map.copyOf(profiles),
                    activities == null ? List.of() : List.copyOf(activities),
                    reflections == null ? List.of() : List.copyOf(reflections),
                    reflectionEventId == null ? "" : reflectionEventId);
        }
    }
}
