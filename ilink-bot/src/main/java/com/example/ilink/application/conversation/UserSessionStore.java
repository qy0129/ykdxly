package com.example.ilink.application.conversation;

import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.weather.WeatherLocation;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户会话状态存储抽象。
 *
 * <p>管理 userId 与 {@link ConversationSession} 之间的关系，负责获取当前会话、
 * 创建新会话和更新会话活跃状态。同时管理用户临时状态，包括人设、待确认绘图提示词、
 * 最近图片、待处理图片、待导出文件、快递查询、天气地点和位置信息。</p>
 *
 * <p>具体存储实现由 platform 层完成。</p>
 */
public final class UserSessionStore {

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
    private final Map<String, PendingDrawRequest> pendingDrawRequests = new ConcurrentHashMap<>();
    private final Map<String, ImageReference> lastImages = new ConcurrentHashMap<>();
    private final Map<String, ImageReference> pendingImages = new ConcurrentHashMap<>();
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

    /** 设置用户当前人设名称。 */
    public void setPersona(String userId, String persona) {
        loadedPersonaUsers.add(userId);
        personas.put(userId, persona);
        database.savePersona(userId, persona);
    }
public interface UserSessionStore {

    // ========== Persona ==========

    void setPersona(String userId, String persona);

    String getPersonaPrompt(String userId);

    String getPersonaName(String userId);

    /** 保存等待用户补充尺寸的绘图提示词。 */
    public void setPendingDraw(String userId, String prompt) {
        setPendingDraw(userId, prompt, "", "");
    }

    public void setPendingDraw(String userId, String prompt, String description, String originalRequest) {
        ensureMediaLoaded(userId);
        PendingDrawRequest request = new PendingDrawRequest(prompt, description, originalRequest,
                System.currentTimeMillis() + PENDING_TTL_MILLIS);
        pendingDrawRequests.put(userId, request);
        database.saveUserState(userId, PENDING_DRAW_KEY, gson.toJson(request));
    }

    /** 查看待确认绘图提示词，但不清除它。 */
    public String peekPendingDraw(String userId) {
        PendingDrawRequest request = getPendingDraw(userId);
        return request == null ? null : request.prompt();
    }

    public PendingDrawRequest getPendingDraw(String userId) {
        ensureMediaLoaded(userId);
        PendingDrawRequest request = pendingDrawRequests.get(userId);
        if (request != null && request.expiresAtMillis() <= System.currentTimeMillis()) {
            clearPendingDraw(userId);
            return null;
        }
        return request;
    }

    /** 清除待确认绘图请求。 */
    public void clearPendingDraw(String userId) {
        loadedMediaUsers.add(userId);
        pendingDrawRequests.remove(userId);
        database.deleteUserState(userId, PENDING_DRAW_KEY);
    }

    /** 保存用户最近一次可继续处理的图片路径。 */
    public void setLastImage(String userId, String path) {
        setLastImage(userId, path, ImageSource.BOT);
    }

    public void setLastImage(String userId, String path, ImageSource source) {
        ensureMediaLoaded(userId);
        ImageReference reference = new ImageReference(path, source, System.currentTimeMillis());
        lastImages.put(userId, reference);
        database.saveUserState(userId, LAST_IMAGE_KEY, gson.toJson(reference));
    }

    /** 获取用户最近一次图片路径。 */
    public String getLastImage(String userId) {
        ImageReference reference = getLastImageReference(userId);
        return reference == null ? null : reference.path();
    }

    public ImageReference getLastImageReference(String userId) {
        ensureMediaLoaded(userId);
        return lastImages.get(userId);
    }

    /** 保存刚收到、等待用户说明处理方式的图片路径。 */
    public void setPendingImage(String userId, String path) {
        ensureMediaLoaded(userId);
        ImageReference reference = new ImageReference(path, ImageSource.USER, System.currentTimeMillis());
        pendingImages.put(userId, reference);
        lastImageAnalyses.remove(userId);
        database.saveUserState(userId, PENDING_IMAGE_KEY, gson.toJson(reference));
    }

    /** 查看待处理图片路径，但不清除它。 */
    public String peekPendingImage(String userId) {
        ImageReference reference = getPendingImageReference(userId);
        return reference == null ? null : reference.path();
    }

    public ImageReference getPendingImageReference(String userId) {
        ensureMediaLoaded(userId);
        return pendingImages.get(userId);
    }

    /** 优先解析本轮用户图片，否则返回最近一张可处理图片。 */
    public ImageReference resolveCurrentImage(String userId) {
        ImageReference pending = getPendingImageReference(userId);
        return pending != null ? pending : getLastImageReference(userId);
    }

    /** 清除待处理图片状态。 */
    public void clearPendingImage(String userId) {
        loadedMediaUsers.add(userId);
        pendingImages.remove(userId);
        database.deleteUserState(userId, PENDING_IMAGE_KEY);
    }

    private void ensureMediaLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedMediaUsers.add(userId)) return;
        loadPendingDrawState(userId);
        loadImageState(userId, LAST_IMAGE_KEY, lastImages, ImageSource.BOT);
        loadImageState(userId, PENDING_IMAGE_KEY, pendingImages, ImageSource.USER);
    }

    private void loadPendingDrawState(String userId) {
        String value = database.loadUserState(userId, PENDING_DRAW_KEY);
        if (value.isBlank()) return;
        try {
            PendingDrawRequest request = value.startsWith("{")
                    ? gson.fromJson(value, PendingDrawRequest.class)
                    : new PendingDrawRequest(value, "", "", System.currentTimeMillis() + PENDING_TTL_MILLIS);
            if (request != null && request.expiresAtMillis() > System.currentTimeMillis()) {
                pendingDrawRequests.put(userId, request);
            } else {
                database.deleteUserState(userId, PENDING_DRAW_KEY);
            }
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, PENDING_DRAW_KEY);
        }
    }

    private void loadImageState(String userId, String key, Map<String, ImageReference> target,
                                ImageSource legacySource) {
        String value = database.loadUserState(userId, key);
        if (value.isBlank()) return;
        try {
            ImageReference reference = value.startsWith("{")
                    ? gson.fromJson(value, ImageReference.class)
                    : new ImageReference(value, legacySource, System.currentTimeMillis());
            if (reference != null && !reference.path().isBlank()) target.put(userId, reference);
        } catch (JsonSyntaxException error) {
            database.deleteUserState(userId, key);
        }
    }
    String getPersonaVoiceStyle(String userId);

    // ========== Draw ==========

    void setPendingDraw(String userId, String prompt);

    String peekPendingDraw(String userId);

    void clearPendingDraw(String userId);

    // ========== Image ==========

    void setLastImage(String userId, String path);

    String getLastImage(String userId);

    void setPendingImage(String userId, String path);

    String peekPendingImage(String userId);

    void clearPendingImage(String userId);

    void setLastImageAnalysis(String userId, String analysis);

    String getLastImageAnalysis(String userId);

    // ========== Pending File Export ==========

    void setPendingFileExport(String userId, String userText, IntentResult route);

    PendingFileExport getPendingFileExport(String userId);

    boolean hasPendingFileExport(String userId);

    void clearPendingFileExport(String userId);

    // ========== Express ==========

    void setPendingExpress(String userId, String stage, String referenceNo);

    PendingExpressState getPendingExpress(String userId);

    boolean hasPendingExpress(String userId);

    void clearPendingExpress(String userId);

    // ========== Weather ==========

    void setPendingWeatherLocations(String userId, List<WeatherLocation> locations, String weatherDay);

    List<WeatherLocation> getPendingWeatherLocations(String userId);

    boolean hasPendingWeatherLocations(String userId);

    String getPendingWeatherDay(String userId);

    void clearPendingWeatherLocations(String userId);

    // ========== Location ==========

    void setCurrentLocation(String userId, String location);

    String getCurrentLocation(String userId);

    String getCurrentCity(String userId);

    // ========== Session Lifecycle ==========

    ConversationSession getCurrentSession(String userId);

    ConversationSession createNewSession(String userId);

    void refreshSession(String userId);

    /** 从数据库查询当前活跃的 {@link ChatSession}，不依赖内存缓存。 */
    ChatSession getActiveSession(String userId);

    /** 便捷方法：直接从 {@link #getActiveSession} 取 sessionId。 */
    default String getActiveSessionId(String userId) {
        ChatSession s = getActiveSession(userId);
        return s == null ? null : s.sessionId();
    }

    // ========== Nested Types ==========

    public enum ImageSource { USER, BOT }

    public record ImageReference(String path, ImageSource source, long createdAtMillis) {
        public ImageReference {
            path = path == null ? "" : path.trim();
            source = source == null ? ImageSource.BOT : source;
        }
    }

    public record PendingDrawRequest(String prompt, String description, String originalRequest,
                                     long expiresAtMillis) {
        public PendingDrawRequest {
            prompt = prompt == null ? "" : prompt.trim();
            description = description == null ? "" : description.trim();
            originalRequest = originalRequest == null ? "" : originalRequest.trim();
        }
    }

    public record PendingFileExport(String userText, IntentResult route, long expiresAtMillis) { }
    record PendingExpressState(String stage, String referenceNo) {
    }

    record PendingFileExport(String userText, IntentResult route, long expiresAtMillis) {
    }

    // ========== Static Utility ==========

    static String extractCity(String location) {
        if (location == null || location.isBlank()) return "";
        var matcher = Pattern.compile("(?:^|省)([^省市区县]{2,10})市").matcher(location.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
