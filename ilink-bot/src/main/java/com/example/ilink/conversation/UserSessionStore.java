package com.example.ilink.conversation;

import com.example.ilink.feature.persona.Personas;
import com.example.ilink.feature.weather.WeatherLocation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 用户临时会话状态存储。
 *
 * <p>保存人设、待确认的绘图提示词、最近图片和待处理图片等短期状态，
 * 不负责保存长期聊天记录或媒体文件。</p>
 */
public final class UserSessionStore {

    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private final Map<String, String> pendingDrawPrompts = new ConcurrentHashMap<>();
    private final Map<String, String> lastImagePaths = new ConcurrentHashMap<>();
    private final Map<String, String> pendingImagePaths = new ConcurrentHashMap<>();
    private final Map<String, List<WeatherLocation>> pendingWeatherLocations = new ConcurrentHashMap<>();
    private final Map<String, String> pendingWeatherDays = new ConcurrentHashMap<>();

    /** 设置用户当前人设名称。 */
    public void setPersona(String userId, String persona) {
        personas.put(userId, persona);
    }

    /** 获取用户当前人设对应的系统提示词。 */
    public String getPersonaPrompt(String userId) {
        String name = personas.getOrDefault(userId, Personas.DEFAULT);
        return name == null ? null : Personas.get(name);
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
}
