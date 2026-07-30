package com.example.ilink.application.conversation;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 保存机器人上一轮明确提出、等待用户确认的可执行建议。 */
public final class SuggestedActionStore {
    private static final Duration TTL = Duration.ofMinutes(15);
    private final Map<String, SuggestedAction> actions = new ConcurrentHashMap<>();

    public void offer(String userId, String requestText) {
        if (userId == null || userId.isBlank() || requestText == null || requestText.isBlank()) return;
        actions.put(userId, new SuggestedAction(requestText.trim(), Instant.now().plus(TTL)));
    }

    public SuggestedAction consume(String userId) {
        SuggestedAction action = actions.remove(userId);
        if (action == null || action.expiresAt().isBefore(Instant.now())) return null;
        return action;
    }

    public void clear(String userId) { actions.remove(userId); }

    public record SuggestedAction(String requestText, Instant expiresAt) { }
}
