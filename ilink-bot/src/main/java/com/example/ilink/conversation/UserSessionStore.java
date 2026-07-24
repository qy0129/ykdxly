package com.example.ilink.conversation;

import com.example.ilink.feature.persona.Personas;
import com.example.ilink.feature.weather.WeatherLocation;
import com.example.ilink.storage.MySqlStore;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户临时会话状态存储。
 *
 * <p>保存人设、待确认的绘图提示词、最近图片和待处理图片等状态；数据库启用时，
 * 人设会跨重启保存，其他短期状态仍只保存在内存。</p>
 */
public final class UserSessionStore {

    private static final String CURRENT_LOCATION_KEY = "current_location";
    private static final String CURRENT_CITY_KEY = "current_city";
    private static final Pattern CITY_PATTERN = Pattern.compile("(?:^|省)([^省市区县]{2,10})市");

    private final MySqlStore database = MySqlStore.getInstance();
    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private final Set<String> loadedPersonaUsers = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingDrawPrompts = new ConcurrentHashMap<>();
    private final Map<String, String> lastImagePaths = new ConcurrentHashMap<>();
    private final Map<String, String> pendingImagePaths = new ConcurrentHashMap<>();
    private final Map<String, List<WeatherLocation>> pendingWeatherLocations = new ConcurrentHashMap<>();
    private final Map<String, String> pendingWeatherDays = new ConcurrentHashMap<>();
    private final Map<String, String> currentLocations = new ConcurrentHashMap<>();
    private final Map<String, String> currentCities = new ConcurrentHashMap<>();
    private final Set<String> loadedLocationUsers = ConcurrentHashMap.newKeySet();

    /** 设置用户当前人设名称。 */
    public void setPersona(String userId, String persona) {
        loadedPersonaUsers.add(userId);
        personas.put(userId, persona);
        database.savePersona(userId, persona);
    }

    /** 获取用户当前人设对应的系统提示词。 */
    public String getPersonaPrompt(String userId) {
        String name = getPersonaName(userId);
        return name == null ? null : Personas.get(name);
    }

    /** 获取当前有效人格名称；已删除或未知的人格会回退到默认人格。 */
    public String getPersonaName(String userId) {
        ensurePersonaLoaded(userId);
        String name = personas.getOrDefault(userId, Personas.DEFAULT);
        return Personas.get(name) == null ? Personas.DEFAULT : name;
    }

    /** 获取当前人格绑定的默认 TTS 音色。 */
    public String getPersonaVoiceStyle(String userId) {
        return Personas.voiceStyle(getPersonaName(userId));
    }

    /** 用户首次访问时从 MySQL 恢复上次选择的人设。 */
    private void ensurePersonaLoaded(String userId) {
        if (!loadedPersonaUsers.add(userId)) return;
        String persona = database.loadPersona(userId);
        if (persona != null && !persona.isBlank()) {
            personas.put(userId, persona);
        }
    }

    /** 保存等待用户补充尺寸的绘图提示词。 */
    public void setPendingDraw(String userId, String prompt) {
        pendingDrawPrompts.put(userId, prompt);
    }

    /** 查看待确认绘图提示词，但不清除它。 */
    public String peekPendingDraw(String userId) {
        return pendingDrawPrompts.get(userId);
    }

    /** 清除待确认绘图请求。 */
    public void clearPendingDraw(String userId) {
        pendingDrawPrompts.remove(userId);
    }

    /** 保存用户最近一次可继续处理的图片路径。 */
    public void setLastImage(String userId, String path) {
        lastImagePaths.put(userId, path);
    }

    /** 获取用户最近一次图片路径。 */
    public String getLastImage(String userId) {
        return lastImagePaths.get(userId);
    }

    /** 保存刚收到、等待用户说明处理方式的图片路径。 */
    public void setPendingImage(String userId, String path) {
        pendingImagePaths.put(userId, path);
    }

    /** 查看待处理图片路径，但不清除它。 */
    public String peekPendingImage(String userId) {
        return pendingImagePaths.get(userId);
    }

    /** 清除待处理图片状态。 */
    public void clearPendingImage(String userId) {
        pendingImagePaths.remove(userId);
    }

    /** 保存等待用户确认的同名天气地点。 */
    public void setPendingWeatherLocations(String userId, List<WeatherLocation> locations, String weatherDay) {
        pendingWeatherLocations.put(userId, List.copyOf(locations));
        pendingWeatherDays.put(userId, weatherDay);
    }

    /** 获取待确认的天气地点。 */
    public List<WeatherLocation> getPendingWeatherLocations(String userId) {
        return pendingWeatherLocations.getOrDefault(userId, List.of());
    }

    /** 判断用户是否正在选择天气地点。 */
    public boolean hasPendingWeatherLocations(String userId) {
        return pendingWeatherLocations.containsKey(userId);
    }

    /** 获取待确认天气查询对应的日期。 */
    public String getPendingWeatherDay(String userId) {
        return pendingWeatherDays.getOrDefault(userId, "today");
    }

    /** 清除待确认的天气地点。 */
    public void clearPendingWeatherLocations(String userId) {
        pendingWeatherLocations.remove(userId);
        pendingWeatherDays.remove(userId);
    }

    /** 记录用户主动提供的当前位置，供“附近有什么好吃的”等连续问法使用。 */
    public void setCurrentLocation(String userId, String location) {
        if (userId == null || userId.isBlank() || location == null || location.isBlank()) return;
        loadedLocationUsers.add(userId);
        String value = location.trim();
        currentLocations.put(userId, value);
        database.saveUserState(userId, CURRENT_LOCATION_KEY, value);
        String city = extractCity(value);
        if (!city.isBlank()) {
            currentCities.put(userId, city);
            database.saveUserState(userId, CURRENT_CITY_KEY, city);
        }
    }

    public String getCurrentLocation(String userId) {
        ensureLocationLoaded(userId);
        return currentLocations.get(userId);
    }

    public String getCurrentCity(String userId) {
        ensureLocationLoaded(userId);
        return currentCities.get(userId);
    }

    private void ensureLocationLoaded(String userId) {
        if (userId == null || userId.isBlank() || !loadedLocationUsers.add(userId)) return;
        String location = database.loadUserState(userId, CURRENT_LOCATION_KEY);
        if (!location.isBlank()) currentLocations.put(userId, location);
        String city = database.loadUserState(userId, CURRENT_CITY_KEY);
        if (city.isBlank()) city = extractCity(location);
        if (!city.isBlank()) currentCities.put(userId, city);
    }

    static String extractCity(String location) {
        if (location == null || location.isBlank()) return "";
        Matcher matcher = CITY_PATTERN.matcher(location.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
