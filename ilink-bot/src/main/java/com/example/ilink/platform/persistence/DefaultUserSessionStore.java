package com.example.ilink.platform.persistence;

import com.example.ilink.application.conversation.ChatSession;
import com.example.ilink.application.conversation.ConversationSession;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.persona.Personas;
import com.example.ilink.capabilities.planning.TodoConflictState;
import com.example.ilink.capabilities.weather.WeatherLocation;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认会话存储实现。
 * 用户级资料保存人格和常用地点；短期交互状态绑定当前 sessionId，避免新对话被旧任务污染。
 */
public final class DefaultUserSessionStore implements UserSessionStore {

    private static final long PENDING_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final String PERSONA = "persona";
    private static final String LOCATION = "current_location";
    private static final String DRAW = "pending_draw";
    private static final String LAST_IMAGE = "last_image";
    private static final String PENDING_IMAGE = "pending_image";
    private static final String IMAGE_ANALYSIS = "last_image_analysis";
    private static final String FILE_EXPORT = "pending_file_export";
    private static final String EXPRESS = "pending_express";
    private static final String TODO_CONFLICT = "pending_todo_conflict";
    private static final String WEATHER = "pending_weather";

    private final MySqlStore database;
    private final Gson gson = new Gson();
    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private final Map<String, String> currentLocations = new ConcurrentHashMap<>();
    private final Set<String> loadedProfiles = ConcurrentHashMap.newKeySet();
    private final Map<String, ConversationSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, PendingDrawRequest> pendingDraws = new ConcurrentHashMap<>();
    private final Map<String, ImageReference> lastImages = new ConcurrentHashMap<>();
    private final Map<String, ImageReference> pendingImages = new ConcurrentHashMap<>();
    private final Map<String, String> imageAnalyses = new ConcurrentHashMap<>();
    private final Map<String, PendingFileExport> pendingExports = new ConcurrentHashMap<>();
    private final Map<String, PendingExpressState> pendingExpress = new ConcurrentHashMap<>();
    private final Map<String, TodoConflictState> pendingTodoConflicts = new ConcurrentHashMap<>();
    private final Map<String, PendingWeatherState> pendingWeather = new ConcurrentHashMap<>();

    public DefaultUserSessionStore() {
        this(MySqlStore.getInstance());
    }

    DefaultUserSessionStore(MySqlStore database) {
        this.database = database;
    }

    @Override
    public void setPersona(String userId, String persona) {
        if (blank(userId)) return;
        personas.put(userId, persona == null || persona.isBlank() ? Personas.DEFAULT : persona.trim());
        database.savePersona(userId, personas.get(userId));
    }

    @Override
    public String getPersonaPrompt(String userId) {
        return Personas.get(getPersonaName(userId));
    }

    @Override
    public String getPersonaName(String userId) {
        loadProfile(userId);
        String persona = personas.getOrDefault(userId, Personas.DEFAULT);
        return Personas.get(persona) == null ? Personas.DEFAULT : persona;
    }

    @Override
    public String getPersonaVoiceStyle(String userId) {
        return Personas.voiceStyle(getPersonaName(userId));
    }

    @Override
    public void setPendingDraw(String userId, String prompt) {
        setPendingDraw(userId, prompt, "", "");
    }

    @Override
    public void setPendingDraw(String userId, String prompt, String description, String originalRequest) {
        PendingDrawRequest request = new PendingDrawRequest(prompt, description, originalRequest, expiresAt());
        pendingDraws.put(stateKey(userId, DRAW), request);
        saveState(userId, DRAW, request);
    }

    @Override
    public String peekPendingDraw(String userId) {
        PendingDrawRequest request = getPendingDraw(userId);
        return request == null ? null : request.prompt();
    }

    @Override
    public PendingDrawRequest getPendingDraw(String userId) {
        PendingDrawRequest request = loadState(userId, DRAW, PendingDrawRequest.class, pendingDraws);
        if (request != null && request.expiresAtMillis() <= System.currentTimeMillis()) {
            clearPendingDraw(userId);
            return null;
        }
        return request;
    }

    @Override
    public void clearPendingDraw(String userId) {
        removeState(userId, DRAW, pendingDraws);
    }

    @Override
    public void setLastImage(String userId, String path) {
        setLastImage(userId, path, ImageSource.BOT);
    }

    @Override
    public void setLastImage(String userId, String path, ImageSource source) {
        ImageReference image = new ImageReference(path, source, System.currentTimeMillis());
        lastImages.put(stateKey(userId, LAST_IMAGE), image);
        saveState(userId, LAST_IMAGE, image);
    }

    @Override
    public String getLastImage(String userId) {
        ImageReference image = getLastImageReference(userId);
        return image == null ? null : image.path();
    }

    @Override
    public ImageReference getLastImageReference(String userId) {
        return loadState(userId, LAST_IMAGE, ImageReference.class, lastImages);
    }

    @Override
    public void setPendingImage(String userId, String path) {
        ImageReference image = new ImageReference(path, ImageSource.USER, System.currentTimeMillis());
        pendingImages.put(stateKey(userId, PENDING_IMAGE), image);
        imageAnalyses.remove(stateKey(userId, IMAGE_ANALYSIS));
        saveState(userId, PENDING_IMAGE, image);
    }

    @Override
    public String peekPendingImage(String userId) {
        ImageReference image = getPendingImageReference(userId);
        return image == null ? null : image.path();
    }

    @Override
    public ImageReference getPendingImageReference(String userId) {
        return loadState(userId, PENDING_IMAGE, ImageReference.class, pendingImages);
    }

    @Override
    public ImageReference resolveCurrentImage(String userId) {
        ImageReference pending = getPendingImageReference(userId);
        return pending == null ? getLastImageReference(userId) : pending;
    }

    @Override
    public void clearPendingImage(String userId) {
        removeState(userId, PENDING_IMAGE, pendingImages);
    }

    @Override
    public void setLastImageAnalysis(String userId, String analysis) {
        String key = stateKey(userId, IMAGE_ANALYSIS);
        if (analysis == null || analysis.isBlank()) {
            imageAnalyses.remove(key);
            deleteState(userId, IMAGE_ANALYSIS);
            return;
        }
        imageAnalyses.put(key, analysis.trim());
        saveState(userId, IMAGE_ANALYSIS, analysis.trim());
    }

    @Override
    public String getLastImageAnalysis(String userId) {
        String key = stateKey(userId, IMAGE_ANALYSIS);
        String value = imageAnalyses.get(key);
        if (value != null) return value;
        value = database.loadUserState(userId, scopedStateKey(userId, IMAGE_ANALYSIS));
        if (!value.isBlank()) imageAnalyses.put(key, value);
        return value.isBlank() ? null : value;
    }

    @Override
    public void setPendingFileExport(String userId, String userText, IntentResult route) {
        PendingFileExport value = new PendingFileExport(userText, route, expiresAt());
        pendingExports.put(stateKey(userId, FILE_EXPORT), value);
        saveState(userId, FILE_EXPORT, value);
    }

    @Override
    public PendingFileExport getPendingFileExport(String userId) {
        PendingFileExport value = loadState(userId, FILE_EXPORT, PendingFileExport.class, pendingExports);
        if (value != null && value.expiresAtMillis() <= System.currentTimeMillis()) {
            clearPendingFileExport(userId);
            return null;
        }
        return value;
    }

    @Override
    public boolean hasPendingFileExport(String userId) {
        return getPendingFileExport(userId) != null;
    }

    @Override
    public void clearPendingFileExport(String userId) {
        removeState(userId, FILE_EXPORT, pendingExports);
    }

    @Override
    public void setPendingExpress(String userId, String stage, String referenceNo) {
        PendingExpressState value = new PendingExpressState(stage, referenceNo == null ? "" : referenceNo.trim());
        pendingExpress.put(stateKey(userId, EXPRESS), value);
        saveState(userId, EXPRESS, value);
    }

    @Override
    public PendingExpressState getPendingExpress(String userId) {
        return loadState(userId, EXPRESS, PendingExpressState.class, pendingExpress);
    }

    @Override
    public boolean hasPendingExpress(String userId) {
        return getPendingExpress(userId) != null;
    }

    @Override
    public void clearPendingExpress(String userId) {
        removeState(userId, EXPRESS, pendingExpress);
    }

    @Override
    public void setPendingTodoConflict(String userId, TodoConflictState state) {
        if (state == null) {
            clearPendingTodoConflict(userId);
            return;
        }
        TodoConflictState value = state.withExpiresAt(expiresAt());
        pendingTodoConflicts.put(stateKey(userId, TODO_CONFLICT), value);
        saveState(userId, TODO_CONFLICT, value);
    }

    @Override
    public TodoConflictState getPendingTodoConflict(String userId) {
        TodoConflictState value = loadState(userId, TODO_CONFLICT,
                TodoConflictState.class, pendingTodoConflicts);
        if (value != null && value.expiresAtMillis() <= System.currentTimeMillis()) {
            clearPendingTodoConflict(userId);
            return null;
        }
        return value;
    }

    @Override
    public boolean hasPendingTodoConflict(String userId) {
        return getPendingTodoConflict(userId) != null;
    }

    @Override
    public void clearPendingTodoConflict(String userId) {
        removeState(userId, TODO_CONFLICT, pendingTodoConflicts);
    }

    @Override
    public void setPendingWeatherLocations(String userId, List<WeatherLocation> locations, String weatherDay) {
        if (locations == null || locations.isEmpty()) return;
        PendingWeatherState value = new PendingWeatherState(List.copyOf(locations),
                weatherDay == null || weatherDay.isBlank() ? "today" : weatherDay, expiresAt());
        pendingWeather.put(stateKey(userId, WEATHER), value);
        saveState(userId, WEATHER, value);
    }

    @Override
    public List<WeatherLocation> getPendingWeatherLocations(String userId) {
        PendingWeatherState value = getPendingWeather(userId);
        return value == null ? List.of() : value.locations();
    }

    @Override
    public boolean hasPendingWeatherLocations(String userId) {
        return getPendingWeather(userId) != null;
    }

    @Override
    public String getPendingWeatherDay(String userId) {
        PendingWeatherState value = getPendingWeather(userId);
        return value == null ? "today" : value.weatherDay();
    }

    @Override
    public void clearPendingWeatherLocations(String userId) {
        removeState(userId, WEATHER, pendingWeather);
    }

    @Override
    public void setCurrentLocation(String userId, String location) {
        if (blank(userId) || blank(location)) return;
        String value = location.trim();
        currentLocations.put(userId, value);
        database.saveUserState(userId, LOCATION, value);
    }

    @Override
    public String getCurrentLocation(String userId) {
        String cached = currentLocations.get(userId);
        if (cached != null) return cached;
        String value = database.loadUserState(userId, LOCATION);
        if (!value.isBlank()) currentLocations.put(userId, value);
        return value.isBlank() ? null : value;
    }

    @Override
    public String getCurrentCity(String userId) {
        return UserSessionStore.extractCity(getCurrentLocation(userId));
    }

    @Override
    public ConversationSession getCurrentSession(String userId) {
        ConversationSession cached = activeSessions.get(userId);
        if (cached != null) return cached;
        ChatSession active = loadActiveSession(userId);
        if (active != null) {
            ConversationSession session = new ConversationSession(userId, active.sessionId(),
                    active.createdTime(), active.lastActiveTime());
            activeSessions.put(userId, session);
            return session;
        }
        return createNewSession(userId);
    }

    @Override
    public ConversationSession createNewSession(String userId) {
        LocalDateTime now = LocalDateTime.now();
        String sessionId = UUID.randomUUID().toString();
        if (database.isAvailable()) {
            database.createSession(sessionId, userId, null);
            database.deactivateOtherSessions(sessionId, userId);
        }
        ConversationSession session = new ConversationSession(userId, sessionId, now, now);
        activeSessions.put(userId, session);
        return session;
    }

    @Override
    public void refreshSession(String userId) {
        ConversationSession current = getCurrentSession(userId);
        ConversationSession refreshed = new ConversationSession(current.userId(), current.sessionId(),
                current.createdAt(), LocalDateTime.now());
        activeSessions.put(userId, refreshed);
        if (database.isAvailable()) database.updateSessionActiveTime(current.sessionId());
    }

    @Override
    public boolean activateSession(String userId, String sessionId) {
        if (blank(userId) || blank(sessionId) || !database.isAvailable()) return false;
        MySqlStore.SessionRow row = database.findSession(sessionId);
        if (row == null || !userId.equals(row.wechatId())) return false;
        database.switchActiveSession(sessionId, userId);
        activeSessions.put(userId, new ConversationSession(userId, sessionId,
                row.createdTime(), LocalDateTime.now()));
        return true;
    }

    @Override
    public ChatSession getActiveSession(String userId) {
        ChatSession active = loadActiveSession(userId);
        if (active != null) return active;
        ConversationSession current = activeSessions.get(userId);
        if (current == null) return null;
        return new ChatSession(current.sessionId(), userId, "", "ACTIVE", current.lastActiveAt(), current.createdAt());
    }

    private ChatSession loadActiveSession(String userId) {
        if (!database.isAvailable()) return null;
        String sessionId = database.findActiveSessionId(userId);
        if (sessionId == null) return null;
        MySqlStore.SessionRow row = database.findSession(sessionId);
        return row == null ? null : new ChatSession(row.sessionId(), row.wechatId(), row.title(), row.status(),
                row.lastActiveTime(), row.createdTime());
    }

    private PendingWeatherState getPendingWeather(String userId) {
        PendingWeatherState value = loadState(userId, WEATHER, PendingWeatherState.class, pendingWeather);
        if (value != null && value.expiresAtMillis() <= System.currentTimeMillis()) {
            clearPendingWeatherLocations(userId);
            return null;
        }
        return value;
    }

    private void loadProfile(String userId) {
        if (blank(userId) || !loadedProfiles.add(userId)) return;
        String value = database.loadPersona(userId);
        if (!blank(value)) personas.put(userId, value);
    }

    private <T> T loadState(String userId, String name, Class<T> type, Map<String, T> values) {
        String key = stateKey(userId, name);
        T cached = values.get(key);
        if (cached != null) return cached;
        String json = database.loadUserState(userId, scopedStateKey(userId, name));
        if (json.isBlank()) return null;
        try {
            T value = gson.fromJson(json, type);
            if (value != null) values.put(key, value);
            return value;
        } catch (JsonSyntaxException error) {
            deleteState(userId, name);
            return null;
        }
    }

    private void saveState(String userId, String name, Object value) {
        database.saveUserState(userId, scopedStateKey(userId, name), gson.toJson(value));
    }

    private void removeState(String userId, String name, Map<String, ?> values) {
        values.remove(stateKey(userId, name));
        deleteState(userId, name);
    }

    private void deleteState(String userId, String name) {
        database.deleteUserState(userId, scopedStateKey(userId, name));
    }

    private String scopedStateKey(String userId, String name) {
        return getCurrentSession(userId).sessionId() + ":" + name;
    }

    private String stateKey(String userId, String name) {
        return getCurrentSession(userId).sessionId() + ":" + name;
    }

    private static long expiresAt() {
        return System.currentTimeMillis() + PENDING_TTL_MILLIS;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record PendingWeatherState(List<WeatherLocation> locations, String weatherDay, long expiresAtMillis) {
        private PendingWeatherState {
            locations = locations == null ? List.of() : List.copyOf(locations);
            weatherDay = weatherDay == null || weatherDay.isBlank() ? "today" : weatherDay;
        }
    }
}
