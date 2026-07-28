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
public interface UserSessionStore {

    // ========== Persona ==========

    void setPersona(String userId, String persona);

    String getPersonaPrompt(String userId);

    String getPersonaName(String userId);

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
