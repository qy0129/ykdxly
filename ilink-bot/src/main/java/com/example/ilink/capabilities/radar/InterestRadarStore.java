package com.example.ilink.capabilities.radar;

import com.example.ilink.platform.persistence.MySqlStore;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializer;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 兴趣主题、新闻去重集合和视频游标的统一存储。 */
public final class InterestRadarStore {
    private static final String STATE_KEY = "interest_radar_v1";
    private static final int MAX_SEEN_ITEMS = 500;
    private static final int MAX_PENDING_ITEMS = 300;

    private final MySqlStore database;
    private final Gson gson = createGson();
    private final ConcurrentHashMap<String, RadarState> states = new ConcurrentHashMap<>();

    public InterestRadarStore(MySqlStore database) {
        this.database = database;
    }

    public static InterestRadarStore inMemory() {
        return new InterestRadarStore(null);
    }

    static Gson createGson() {
        return new GsonBuilder()
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonSerializer<LocalDateTime>) (value, type, context) ->
                                new JsonPrimitive(value.toString()))
                .registerTypeAdapter(LocalDateTime.class,
                        (JsonDeserializer<LocalDateTime>) (json, type, context) ->
                                LocalDateTime.parse(json.getAsString()))
                .create();
    }

    public synchronized List<InterestTopic> topics(String userId) {
        return List.copyOf(state(userId).topics);
    }

    public synchronized List<InterestTopic> addTopics(String userId, List<String> names) {
        RadarState state = state(userId);
        List<InterestTopic> added = new ArrayList<>();
        for (String raw : names == null ? List.<String>of() : names) {
            String name = raw == null ? "" : raw.trim();
            if (name.isBlank()) continue;
            state.excludedTopicNames.remove(normalizedName(name));
            InterestTopic existing = state.topics.stream()
                    .filter(topic -> topic.name().equalsIgnoreCase(name))
                    .findFirst().orElse(null);
            if (existing != null) {
                if (!existing.enabled()) {
                    state.topics.remove(existing);
                    InterestTopic enabled = existing.withEnabled(true);
                    state.topics.add(enabled);
                    added.add(enabled);
                }
                continue;
            }
            InterestTopic topic = new InterestTopic("TOPIC-" + shortId(), name,
                    List.of(name), List.of(), true, LocalDateTime.now());
            state.topics.add(topic);
            added.add(topic);
        }
        persist(userId, state);
        return List.copyOf(added);
    }

    public synchronized boolean syncPlanTopics(String userId, String fingerprint, List<String> names) {
        RadarState state = state(userId);
        if (fingerprint != null && fingerprint.equals(state.planFingerprint)) return false;
        state.topics.removeIf(topic -> topic.origin() != RadarTopicOrigin.EXPLICIT_USER);
        for (String raw : names == null ? List.<String>of() : names) {
            String name = raw == null ? "" : raw.trim();
            if (name.isBlank() || state.excludedTopicNames.contains(normalizedName(name))
                    || state.topics.stream()
                    .anyMatch(topic -> topic.name().equalsIgnoreCase(name))) continue;
            LocalDateTime now = LocalDateTime.now();
            state.topics.add(new InterestTopic("TOPIC-" + shortId(), name, List.of(name), List.of(),
                    !state.disabledAutoTopicNames.contains(normalizedName(name)), now,
                    RadarTopicOrigin.PLAN_TASK, RadarTopicPriority.HIGH,
                    0.9, now, null));
        }
        state.planFingerprint = fingerprint == null ? "" : fingerprint;
        persist(userId, state);
        return true;
    }

    public synchronized String planFingerprint(String userId) {
        return state(userId).planFingerprint;
    }

    public synchronized boolean setEnabled(String userId, String selector, boolean enabled) {
        RadarState state = state(userId);
        for (int index = 0; index < state.topics.size(); index++) {
            InterestTopic topic = state.topics.get(index);
            if (topic.id().equalsIgnoreCase(selector) || topic.name().contains(selector)) {
                state.topics.set(index, topic.withEnabled(enabled));
                if (topic.origin() != RadarTopicOrigin.EXPLICIT_USER) {
                    if (enabled) state.disabledAutoTopicNames.remove(normalizedName(topic.name()));
                    else state.disabledAutoTopicNames.add(normalizedName(topic.name()));
                }
                persist(userId, state);
                return true;
            }
        }
        return false;
    }

    public synchronized String removeTopic(String userId, String selector) {
        RadarState state = state(userId);
        InterestTopic found = state.topics.stream()
                .filter(topic -> topic.id().equalsIgnoreCase(selector) || topic.name().contains(selector))
                .findFirst().orElse(null);
        if (found == null) return "";
        state.topics.remove(found);
        state.excludedTopicNames.add(normalizedName(found.name()));
        persist(userId, state);
        return found.name();
    }

    public synchronized Set<String> seenNewsKeys(String userId) {
        return Set.copyOf(state(userId).seenNewsKeys);
    }

    public synchronized void markNewsSeen(String userId, List<String> keys) {
        RadarState state = state(userId);
        state.seenNewsKeys.addAll(keys == null ? List.of() : keys);
        while (state.seenNewsKeys.size() > MAX_SEEN_ITEMS) {
            String first = state.seenNewsKeys.iterator().next();
            state.seenNewsKeys.remove(first);
        }
        persist(userId, state);
    }

    public synchronized VideoFeedSession videoSession(String userId) {
        return state(userId).videoSession;
    }

    public synchronized void saveVideoSession(String userId, VideoFeedSession session) {
        RadarState state = state(userId);
        state.videoSession = session;
        persist(userId, state);
    }

    public synchronized Set<String> seenVideoUrls(String userId) {
        return Set.copyOf(state(userId).seenVideoUrls);
    }

    public synchronized void markVideosSeen(String userId, List<String> urls) {
        RadarState state = state(userId);
        state.seenVideoUrls.addAll(urls == null ? List.of() : urls);
        trim(state.seenVideoUrls);
        persist(userId, state);
    }

    public synchronized LocalDateTime lastVideoPushAt(String userId) {
        return state(userId).lastVideoPushAt;
    }

    public synchronized void markVideoPushed(String userId, LocalDateTime pushedAt) {
        RadarState state = state(userId);
        state.lastVideoPushAt = pushedAt;
        persist(userId, state);
    }

    public synchronized RadarPreferences preferences(String userId) {
        RadarState state = state(userId);
        if (state.preferences == null) state.preferences = RadarPreferences.defaults();
        return state.preferences;
    }

    public synchronized void savePreferences(String userId, RadarPreferences preferences) {
        RadarState state = state(userId);
        state.preferences = preferences == null ? RadarPreferences.defaults() : preferences;
        persist(userId, state);
    }

    public synchronized void saveCandidates(String userId, List<RadarContentItem> candidates) {
        RadarState state = state(userId);
        LinkedHashMap<String, RadarContentItem> merged = new LinkedHashMap<>();
        for (RadarContentItem item : state.pendingContent) {
            if (!state.pushedContentKeys.contains(item.eventKey())) merged.put(item.eventKey(), item);
        }
        for (RadarContentItem item : candidates == null ? List.<RadarContentItem>of() : candidates) {
            if (item.eventKey().isBlank() || state.pushedContentKeys.contains(item.eventKey())) continue;
            merged.merge(item.eventKey(), item,
                    (left, right) -> left.score() >= right.score() ? left : right);
        }
        state.pendingContent = merged.values().stream()
                .sorted((left, right) -> Integer.compare(right.score(), left.score()))
                .limit(MAX_PENDING_ITEMS).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        persist(userId, state);
    }

    public synchronized List<RadarContentItem> pendingCandidates(String userId) {
        return List.copyOf(state(userId).pendingContent);
    }

    public synchronized void markContentPushed(String userId, List<String> eventKeys,
                                               LocalDateTime pushedAt) {
        RadarState state = state(userId);
        state.pushedContentKeys.addAll(eventKeys == null ? List.of() : eventKeys);
        trim(state.pushedContentKeys);
        state.pendingContent.removeIf(item -> state.pushedContentKeys.contains(item.eventKey()));
        state.lastDigestAt = pushedAt;
        String day = pushedAt == null ? "" : pushedAt.toLocalDate().toString();
        if (!day.equals(state.pushCountDate)) {
            state.pushCountDate = day;
            state.dailyPushCount = 0;
        }
        state.dailyPushCount++;
        persist(userId, state);
    }

    public synchronized LocalDateTime lastDigestAt(String userId) {
        return state(userId).lastDigestAt;
    }

    public synchronized int dailyPushCount(String userId, LocalDateTime now) {
        RadarState state = state(userId);
        String day = now.toLocalDate().toString();
        return day.equals(state.pushCountDate) ? state.dailyPushCount : 0;
    }

    public synchronized InterestTopic nextVideoTopic(String userId, List<InterestTopic> enabledTopics) {
        if (enabledTopics == null || enabledTopics.isEmpty()) return null;
        RadarState state = state(userId);
        int index = Math.floorMod(state.videoTopicCursor, enabledTopics.size());
        state.videoTopicCursor = (index + 1) % enabledTopics.size();
        persist(userId, state);
        return enabledTopics.get(index);
    }

    public synchronized List<InterestTopic> nextDiscoveryTopics(String userId,
                                                                List<InterestTopic> enabledTopics,
                                                                int limit) {
        if (enabledTopics == null || enabledTopics.isEmpty()) return List.of();
        RadarState state = state(userId);
        int count = Math.min(Math.max(1, limit), enabledTopics.size());
        List<InterestTopic> selected = new ArrayList<>();
        int start = Math.floorMod(state.discoveryTopicCursor, enabledTopics.size());
        for (int offset = 0; offset < count; offset++) {
            selected.add(enabledTopics.get((start + offset) % enabledTopics.size()));
        }
        state.discoveryTopicCursor = (start + count) % enabledTopics.size();
        persist(userId, state);
        return List.copyOf(selected);
    }

    private RadarState state(String userId) {
        return states.computeIfAbsent(userId, this::load);
    }

    private RadarState load(String userId) {
        if (database == null || !database.isAvailable()) return new RadarState();
        String json = database.loadUserState(userId, STATE_KEY);
        if (json == null || json.isBlank()) return new RadarState();
        try {
            RadarState loaded = gson.fromJson(json, RadarState.class);
            return loaded == null ? new RadarState() : loaded.normalize();
        } catch (RuntimeException error) {
            System.err.println("[兴趣雷达] 状态解析失败，使用空状态: " + error.getMessage());
            return new RadarState();
        }
    }

    private void persist(String userId, RadarState state) {
        if (database != null && database.isAvailable()) {
            database.saveUserState(userId, STATE_KEY, gson.toJson(state));
        }
    }

    private static void trim(LinkedHashSet<String> values) {
        while (values.size() > MAX_SEEN_ITEMS) {
            values.remove(values.iterator().next());
        }
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "")
                .substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private static String normalizedName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static final class RadarState {
        private List<InterestTopic> topics = new ArrayList<>();
        private LinkedHashSet<String> seenNewsKeys = new LinkedHashSet<>();
        private LinkedHashSet<String> seenVideoUrls = new LinkedHashSet<>();
        private LinkedHashSet<String> pushedContentKeys = new LinkedHashSet<>();
        private List<RadarContentItem> pendingContent = new ArrayList<>();
        private VideoFeedSession videoSession;
        private LocalDateTime lastVideoPushAt;
        private LocalDateTime lastDigestAt;
        private int videoTopicCursor;
        private int discoveryTopicCursor;
        private String planFingerprint = "";
        private LinkedHashSet<String> excludedTopicNames = new LinkedHashSet<>();
        private LinkedHashSet<String> disabledAutoTopicNames = new LinkedHashSet<>();
        private RadarPreferences preferences = RadarPreferences.defaults();
        private String pushCountDate = "";
        private int dailyPushCount;

        private RadarState normalize() {
            if (topics == null) topics = new ArrayList<>();
            if (seenNewsKeys == null) seenNewsKeys = new LinkedHashSet<>();
            if (seenVideoUrls == null) seenVideoUrls = new LinkedHashSet<>();
            if (pushedContentKeys == null) pushedContentKeys = new LinkedHashSet<>();
            if (pendingContent == null) pendingContent = new ArrayList<>();
            if (planFingerprint == null) planFingerprint = "";
            if (excludedTopicNames == null) excludedTopicNames = new LinkedHashSet<>();
            if (disabledAutoTopicNames == null) disabledAutoTopicNames = new LinkedHashSet<>();
            if (preferences == null) preferences = RadarPreferences.defaults();
            if (pushCountDate == null) pushCountDate = "";
            return this;
        }
    }
}
