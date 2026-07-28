package com.example.ilink.platform.persistence;

import com.example.ilink.application.conversation.ChatSession;
import com.example.ilink.application.conversation.ConversationSession;
import com.example.ilink.application.conversation.UserSessionStore;
import com.example.ilink.capabilities.persona.Personas;
import com.example.ilink.capabilities.weather.WeatherLocation;
import com.example.ilink.application.routing.IntentResult;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户会话状态存储默认实现。
 *
 * <p>保存人设、待确认的绘图提示词、最近图片和待处理图片等状态；数据库启用时，
 * 人设会跨重启保存，其他短期状态仍只保存在内存。</p>
 */
public final class DefaultUserSessionStore implements UserSessionStore {

    private static final String CURRENT_LOCATION_KEY = "current_location";
    private static final String CURRENT_CITY_KEY = "current_city";
    private static final String PENDING_EXPRESS_KEY = "pending_express";
    private static final String PENDING_WEATHER_KEY = "pending_weather_locations";
    private static final String PENDING_DRAW_KEY = "pending_draw";
    private static final String LAST_IMAGE_KEY = "last_image";
    private static final String PENDING_IMAGE_KEY = "pending_image";
    private static final String PENDING_FILE_EXPORT_KEY = "pending_file_export";
    private static final long PENDING_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final long CURRENT_LOCATION_TTL_MILLIS = 24L * 60 * 60 * 1000;
    private static final Pattern CITY_PATTERN = Pattern.compile("(?:^|省)([^省市区县]{2,10})市");

    private final MySqlStore database = MySqlStore.getInstance();
    private final Gson gson = new Gson();
    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private final Set<String> loadedPersonaUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingDrawPrompts = new ConcurrentHashMap<>();
    private final Map<String, String> lastImagePaths = new ConcurrentHashMap<>();
    private final Map<String, String> pendingImagePaths = new ConcurrentHashMap<>();
    private final Map<String, String> lastImageAnalyses = new ConcurrentHashMap<>();
    private final Map<String, List<WeatherLocation>> pendingWeatherLocations = new ConcurrentHashMap<>();
    private final Map<String, String> pendingWeatherDays = new ConcurrentHashMap<>();
    private final Set<String> loadedWeatherUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingFileExport> pendingFileExports = new ConcurrentHashMap<>();
    private final Set<String> loadedMediaUsers = ConcurrentHashMap.newKeySet();
    private final Set<String> loadedFileExportUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> currentLocations = new ConcurrentHashMap<>();
    private final Map<String, String> currentCities = new ConcurrentHashMap<>();
    private final Set<String> loadedLocationUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, PendingExpressState> pendingExpressStates = new ConcurrentHashMap<>();
    private final Set<String> loadedExpressUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void setPersona(String userId, String persona) {
        loadedPersonaUsers.add(userId);
        personas.put(userId, persona);
        database.savePersona(userId, persona);
    }

    @Override
    public String getPersonaPrompt(String userId) {
        String name = getPersonaName(userId);
        return name == null ? null : Personas.get(name);
    }

    @Override
    public String getPersonaName(String userId) {
        ensurePersonaLoaded(userId);
        String name = personas.getOrDefault(userId, Personas.DEFAULT);
        return Personas.get(name) == null ? Personas.DEFAULT : name;
    }

    @Override
    public String getPersonaVoiceStyle(String userId) {
        return Personas.voiceStyle(getPersonaName(userId));
    }

    private void ensurePersonaLoaded(String userId) {
        if (!loadedPersonaUsers.add(userId)) return;
        String persona = database.loadPersona(userId);
        if (persona != null && !persona.isBlank()) {
            personas.put(userId, persona);
        }
    }

    @Override
    public void setPendingDraw(String userId, String prompt) {
        ensureMediaLoaded(userId);
        pendingDrawPrompts.put(userId, prompt);
        database.saveUserState(userId, PENDING_DRAW_KEY, prompt);
    }

    @Override
    public String peekPendingDraw(String userId) {
        ensureMediaLoaded(userId);
        return pendingDrawPrompts.get(userId);
    }

    @Override
    public void clearPendingDraw(String userId) {
        loadedMediaUsers.add(userId);
        pendingDrawPrompts.remove(userId);
        database.deleteUserState(userId, PENDING_DRAW_KEY);
    }

    @Override
    public void setLastImage(String userId, String path) {
        ensureMediaLoaded(userId);
        lastImagePaths.put(userId, path);
        database.saveUserState(userId, LAST_IMAGE_KEY, path);
    }

    @Override
    public String getLastImage(String userId) {
        ensureMediaLoaded(userId);
        return lastImagePaths.get(userId);
    }

    @Override
    public void setPendingImage(String userId, String path) {
        ensureMediaLoaded(userId);
        pendingImagePaths.put(userId, path);
        lastImageAnalyses.remove(userId);
        database.saveUserState(userId, PENDING_IMAGE_KEY, path);
    }

    @Override
    public String peekPendingImage(String userId) {
        ensureMediaLoaded(userId);
        return pendingImagePaths.get(userId);
    }

    @Override
    public void clearPendingImage(String userId) {
        loadedMediaUsers.add(userId);
        pendingImagePaths.remove(userId);
        database.deleteUserState(userId, PENDING_IMAGE_KEY);
    }

    private void ensureMediaLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedMediaUsers.add(userId)) return;
        loadStringState(userId, PENDING_DRAW_KEY, pendingDrawPrompts);
        loadStringState(userId, LAST_IMAGE_KEY, lastImagePaths);
        loadStringState(userId, PENDING_IMAGE_KEY, pendingImagePaths);
    }

    private void loadStringState(String userId, String key, Map<String, String> target) {
        String value = database.loadUserState(userId, key);
        if (!value.isBlank()) target.put(userId, value);
    }

    @Override
    public void setPendingFileExport(String userId, String userText, IntentResult route) {
        if (userId == null || userId.isBlank() || route == null) return;
        loadedFileExportUsers.add(userId);
        PendingFileExport value = new PendingFileExport(userText, route,
                System.currentTimeMillis() + PENDING_TTL_MILLIS);
        pendingFileExports.put(userId, value);
        database.saveUserState(userId, PENDING_FILE_EXPORT_KEY, gson.toJson(value));
    }

    @Override
    public PendingFileExport getPendingFileExport(String userId) {
        ensureFileExportLoaded(userId);
        return pendingFileExports.get(userId);
    }

    @Override
    public boolean hasPendingFileExport(String userId) {
        return getPendingFileExport(userId) != null;
    }

    @Override
    public void clearPendingFileExport(String userId) {
        loadedFileExportUsers.add(userId);
        pendingFileExports.remove(userId);
        database.deleteUserState(userId, PENDING_FILE_EXPORT_KEY);
    }

    private void ensureFileExportLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedFileExportUsers.add(userId)) return;
        String value = database.loadUserState(userId, PENDING_FILE_EXPORT_KEY);
        if (value.isBlank()) return;
        try {
            PendingFileExport state = gson.fromJson(value, PendingFileExport.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()) {
                pendingFileExports.put(userId, state);
            } else {
                database.deleteUserState(userId, PENDING_FILE_EXPORT_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_FILE_EXPORT_KEY);
        }
    }

    @Override
    public void setPendingExpress(String userId, String stage, String referenceNo) {
        if (userId == null || userId.isBlank() || stage == null || stage.isBlank()) return;
        loadedExpressUsers.add(userId);
        PendingExpressState state = new PendingExpressState(stage,
                referenceNo == null ? "" : referenceNo.trim());
        pendingExpressStates.put(userId, state);
        database.saveUserState(userId, PENDING_EXPRESS_KEY, gson.toJson(state));
    }

    @Override
    public PendingExpressState getPendingExpress(String userId) {
        ensureExpressLoaded(userId);
        return pendingExpressStates.get(userId);
    }

    @Override
    public boolean hasPendingExpress(String userId) {
        return getPendingExpress(userId) != null;
    }

    @Override
    public void clearPendingExpress(String userId) {
        loadedExpressUsers.add(userId);
        pendingExpressStates.remove(userId);
        database.deleteUserState(userId, PENDING_EXPRESS_KEY);
    }

    private void ensureExpressLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedExpressUsers.add(userId)) return;
        String value = database.loadUserState(userId, PENDING_EXPRESS_KEY);
        if (value.isBlank()) return;
        try {
            PendingExpressState state = gson.fromJson(value, PendingExpressState.class);
            if (state != null && state.stage() != null && !state.stage().isBlank()) {
                pendingExpressStates.put(userId, state);
            }
        } catch (JsonSyntaxException ignored) {
            database.deleteUserState(userId, PENDING_EXPRESS_KEY);
        }
    }

    @Override
    public void setLastImageAnalysis(String userId, String analysis) {
        if (analysis == null || analysis.isBlank()) {
            lastImageAnalyses.remove(userId);
        } else {
            lastImageAnalyses.put(userId, analysis);
        }
    }

    @Override
    public String getLastImageAnalysis(String userId) {
        return lastImageAnalyses.get(userId);
    }

    @Override
    public void setPendingWeatherLocations(String userId, List<WeatherLocation> locations, String weatherDay) {
        if (userId == null || userId.isBlank() || locations == null || locations.isEmpty()) return;
        loadedWeatherUsers.add(userId);
        List<WeatherLocation> candidates = List.copyOf(locations);
        String day = weatherDay == null || weatherDay.isBlank() ? "today" : weatherDay;
        pendingWeatherLocations.put(userId, candidates);
        pendingWeatherDays.put(userId, day);
        database.saveUserState(userId, PENDING_WEATHER_KEY,
                gson.toJson(new PendingWeatherState(candidates, day,
                        System.currentTimeMillis() + PENDING_TTL_MILLIS)));
    }

    @Override
    public List<WeatherLocation> getPendingWeatherLocations(String userId) {
        ensureWeatherLoaded(userId);
        return pendingWeatherLocations.getOrDefault(userId, List.of());
    }

    @Override
    public boolean hasPendingWeatherLocations(String userId) {
        ensureWeatherLoaded(userId);
        return pendingWeatherLocations.containsKey(userId);
    }

    @Override
    public String getPendingWeatherDay(String userId) {
        ensureWeatherLoaded(userId);
        return pendingWeatherDays.getOrDefault(userId, "today");
    }

    @Override
    public void clearPendingWeatherLocations(String userId) {
        loadedWeatherUsers.add(userId);
        pendingWeatherLocations.remove(userId);
        pendingWeatherDays.remove(userId);
        database.deleteUserState(userId, PENDING_WEATHER_KEY);
    }

    private void ensureWeatherLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedWeatherUsers.add(userId)) return;
        String value = database.loadUserState(userId, PENDING_WEATHER_KEY);
        if (value.isBlank()) return;
        try {
            PendingWeatherState state = gson.fromJson(value, PendingWeatherState.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()
                    && !state.locations().isEmpty()) {
                pendingWeatherLocations.put(userId, state.locations());
                pendingWeatherDays.put(userId, state.weatherDay());
            } else {
                database.deleteUserState(userId, PENDING_WEATHER_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_WEATHER_KEY);
        }
    }

    @Override
    public void setCurrentLocation(String userId, String location) {
        if (userId == null || userId.isBlank() || location == null || location.isBlank()) return;
        loadedLocationUsers.add(userId);
        String value = location.trim();
        currentLocations.put(userId, value);
        String city = UserSessionStore.extractCity(value);
        if (!city.isBlank()) {
            currentCities.put(userId, city);
        }
        database.saveUserState(userId, CURRENT_LOCATION_KEY, gson.toJson(new CurrentLocationState(
                value, city, System.currentTimeMillis() + CURRENT_LOCATION_TTL_MILLIS)));
        database.deleteUserState(userId, CURRENT_CITY_KEY);
    }

    @Override
    public String getCurrentLocation(String userId) {
        ensureLocationLoaded(userId);
        return currentLocations.get(userId);
    }

    @Override
    public String getCurrentCity(String userId) {
        ensureLocationLoaded(userId);
        return currentCities.get(userId);
    }

    private void ensureLocationLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedLocationUsers.add(userId)) return;
        String value = database.loadUserState(userId, CURRENT_LOCATION_KEY);
        if (value.isBlank()) return;
        try {
            CurrentLocationState state = gson.fromJson(value, CurrentLocationState.class);
            if (state != null && state.expiresAtMillis() > System.currentTimeMillis()
                    && state.location() != null && !state.location().isBlank()) {
                currentLocations.put(userId, state.location());
                String city = state.city().isBlank() ? UserSessionStore.extractCity(state.location()) : state.city();
                if (!city.isBlank()) currentCities.put(userId, city);
            } else {
                database.deleteUserState(userId, CURRENT_LOCATION_KEY);
                database.deleteUserState(userId, CURRENT_CITY_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, CURRENT_LOCATION_KEY);
            database.deleteUserState(userId, CURRENT_CITY_KEY);
        }
    }

    @Override
    public ConversationSession getCurrentSession(String userId) {
        ConversationSession session = sessions.get(userId);
        if (session == null && database.isAvailable()) {
            String activeSessionId = database.findActiveSessionId(userId);
            if (activeSessionId != null) {
                session = new ConversationSession(userId, activeSessionId,
                        LocalDateTime.now(), LocalDateTime.now(), List.of(), Map.of());
                sessions.put(userId, session);
            }
        }
        return session;
    }

    @Override
    public ChatSession getActiveSession(String userId) {
        if (!database.isAvailable()) return null;
        String sessionId = database.findActiveSessionId(userId);
        if (sessionId == null) return null;
        MySqlStore.SessionRow row = database.findSession(sessionId);
        if (row == null) return null;
        return new ChatSession(row.sessionId(), row.wechatId(), row.title(), row.status(),
                row.lastActiveTime(), row.createdTime());
    }

    @Override
    public ConversationSession createNewSession(String userId) {
        var now = LocalDateTime.now();
        var sessionId = UUID.randomUUID().toString();
        if (database.isAvailable()) {
            database.createSession(sessionId, userId, null);
        }
        var session = new ConversationSession(userId, sessionId, now, now, List.of(), Map.of());
        sessions.put(userId, session);
        return session;
    }

    @Override
    public void refreshSession(String userId) {
        var session = sessions.get(userId);
        if (session != null) {
            var refreshed = new ConversationSession(
                    session.userId(),
                    session.sessionId(),
                    session.createdAt(),
                    LocalDateTime.now(),
                    session.messages(),
                    session.taskState());
            sessions.put(userId, refreshed);
            if (database.isAvailable()) {
                database.updateSessionActiveTime(session.sessionId());
            }
        }
    }

    private record PendingWeatherState(List<WeatherLocation> locations, String weatherDay,
                                       long expiresAtMillis) {
        private PendingWeatherState {
            locations = locations == null ? List.of() : List.copyOf(locations);
            weatherDay = weatherDay == null || weatherDay.isBlank() ? "today" : weatherDay;
        }
    }

    private record CurrentLocationState(String location, String city, long expiresAtMillis) {
        private CurrentLocationState {
            location = location == null ? "" : location.trim();
            city = city == null ? "" : city.trim();
        }
    }
}
