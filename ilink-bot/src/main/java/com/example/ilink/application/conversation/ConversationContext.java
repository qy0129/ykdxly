package com.example.ilink.application.conversation;

import com.example.ilink.platform.persistence.MySqlStore;

import java.util.List;

public record ConversationContext(String sessionId, String persona, String summary,
                                  List<MySqlStore.ChatEntry> recentMessages) {
    public ConversationContext {
        persona = persona == null ? "" : persona;
        summary = summary == null ? "" : summary;
        recentMessages = recentMessages == null ? List.of() : List.copyOf(recentMessages);
    }
}
