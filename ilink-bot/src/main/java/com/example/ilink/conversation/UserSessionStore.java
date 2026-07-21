package com.example.ilink.conversation;

import com.example.ilink.feature.persona.Personas;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UserSessionStore {

    private final Map<String, String> personas = new ConcurrentHashMap<>();
    private final Map<String, String> pendingDrawPrompts = new ConcurrentHashMap<>();
    private final Map<String, String> lastImagePaths = new ConcurrentHashMap<>();
    private final Map<String, String> pendingImagePaths = new ConcurrentHashMap<>();

    public void setPersona(String userId, String persona) {
        personas.put(userId, persona);
    }

    public String getPersonaPrompt(String userId) {
        String name = personas.getOrDefault(userId, Personas.DEFAULT);
        return name == null ? null : Personas.get(name);
    }

    public void setPendingDraw(String userId, String prompt) {
        pendingDrawPrompts.put(userId, prompt);
    }

    public String peekPendingDraw(String userId) {
        return pendingDrawPrompts.get(userId);
    }

    public void clearPendingDraw(String userId) {
        pendingDrawPrompts.remove(userId);
    }

    public void setLastImage(String userId, String path) {
        lastImagePaths.put(userId, path);
    }

    public String getLastImage(String userId) {
        return lastImagePaths.get(userId);
    }

    public void setPendingImage(String userId, String path) {
        pendingImagePaths.put(userId, path);
    }

    public String peekPendingImage(String userId) {
        return pendingImagePaths.get(userId);
    }

    public void clearPendingImage(String userId) {
        pendingImagePaths.remove(userId);
    }
}
