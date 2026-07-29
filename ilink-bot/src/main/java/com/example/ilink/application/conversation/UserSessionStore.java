package com.example.ilink.application.conversation;

import com.example.ilink.application.routing.IntentResult;
import com.example.ilink.capabilities.weather.WeatherLocation;

import java.util.List;
import java.util.regex.Pattern;

/** 用户长期资料与当前会话状态的访问接口。 */
public interface UserSessionStore {

    void setPersona(String userId, String persona);

    String getPersonaPrompt(String userId);

    String getPersonaName(String userId);

    String getPersonaVoiceStyle(String userId);

    void setPendingDraw(String userId, String prompt);

    void setPendingDraw(String userId, String prompt, String description, String originalRequest);

    String peekPendingDraw(String userId);

    PendingDrawRequest getPendingDraw(String userId);

    void clearPendingDraw(String userId);

    void setLastImage(String userId, String path);

    void setLastImage(String userId, String path, ImageSource source);

    String getLastImage(String userId);

    ImageReference getLastImageReference(String userId);

    void setPendingImage(String userId, String path);

    String peekPendingImage(String userId);

    ImageReference getPendingImageReference(String userId);

    ImageReference resolveCurrentImage(String userId);

    void clearPendingImage(String userId);

    void setLastImageAnalysis(String userId, String analysis);

    String getLastImageAnalysis(String userId);

    void setPendingFileExport(String userId, String userText, IntentResult route);

    PendingFileExport getPendingFileExport(String userId);

    boolean hasPendingFileExport(String userId);

    void clearPendingFileExport(String userId);

    void setPendingExpress(String userId, String stage, String referenceNo);

    PendingExpressState getPendingExpress(String userId);

    boolean hasPendingExpress(String userId);

    void clearPendingExpress(String userId);

    void setPendingWeatherLocations(String userId, List<WeatherLocation> locations, String weatherDay);

    List<WeatherLocation> getPendingWeatherLocations(String userId);

    boolean hasPendingWeatherLocations(String userId);

    String getPendingWeatherDay(String userId);

    void clearPendingWeatherLocations(String userId);

    void setCurrentLocation(String userId, String location);

    String getCurrentLocation(String userId);

    String getCurrentCity(String userId);

    ConversationSession getCurrentSession(String userId);

    ConversationSession createNewSession(String userId);

    void refreshSession(String userId);

    boolean activateSession(String userId, String sessionId);

    ChatSession getActiveSession(String userId);

    default String getActiveSessionId(String userId) {
        ChatSession session = getActiveSession(userId);
        return session == null ? null : session.sessionId();
    }

    enum ImageSource { USER, BOT }

    record ImageReference(String path, ImageSource source, long createdAtMillis) {
        public ImageReference {
            path = path == null ? "" : path.trim();
            source = source == null ? ImageSource.BOT : source;
        }
    }

    record PendingDrawRequest(String prompt, String description, String originalRequest, long expiresAtMillis) {
        public PendingDrawRequest {
            prompt = prompt == null ? "" : prompt.trim();
            description = description == null ? "" : description.trim();
            originalRequest = originalRequest == null ? "" : originalRequest.trim();
        }
    }

    record PendingFileExport(String userText, IntentResult route, long expiresAtMillis) { }

    record PendingExpressState(String stage, String referenceNo) { }

    static String extractCity(String location) {
        if (location == null || location.isBlank()) return "";
        var matcher = Pattern.compile("(?:^|省)([^省市区县]{2,10})市").matcher(location.trim());
        return matcher.find() ? matcher.group(1).trim() : "";
    }
}
