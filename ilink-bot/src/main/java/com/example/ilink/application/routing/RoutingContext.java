package com.example.ilink.application.routing;

import com.example.ilink.platform.persistence.MySqlStore;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** 路由模型所需的完整上下文；所有路由阶段共享同一份快照。 */
public record RoutingContext(
        String persona,
        String memories,
        String conversationSummary,
        List<MySqlStore.ChatEntry> recentMessages,
        String knowledgeContext,
        String currentLocation,
        String currentCity,
        ZonedDateTime currentTime,
        IntentContext mediaContext,
        Map<String, Boolean> pendingStates) {

    public RoutingContext {
        persona = text(persona);
        memories = text(memories);
        conversationSummary = text(conversationSummary);
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
        knowledgeContext = text(knowledgeContext);
        currentLocation = text(currentLocation);
        currentCity = text(currentCity);
        currentTime = currentTime == null ? ZonedDateTime.now() : currentTime;
        mediaContext = mediaContext == null
                ? new IntentContext(false, false, false, false, false) : mediaContext;
        pendingStates = pendingStates == null ? Map.of() : Map.copyOf(pendingStates);
    }

    public static RoutingContext minimal(IntentContext context) {
        return new RoutingContext("", "", "", List.of(), "", "", "",
                ZonedDateTime.now(), context, Map.of());
    }

    /** 兼容未接入知识库上下文的旧调用方。 */
    public RoutingContext(String persona, String memories, String conversationSummary,
                          List<MySqlStore.ChatEntry> recentMessages, String currentLocation,
                          String currentCity, ZonedDateTime currentTime, IntentContext mediaContext,
                          Map<String, Boolean> pendingStates) {
        this(persona, memories, conversationSummary, recentMessages, "", currentLocation,
                currentCity, currentTime, mediaContext, pendingStates);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
