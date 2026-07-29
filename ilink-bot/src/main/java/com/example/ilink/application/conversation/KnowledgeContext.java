package com.example.ilink.application.conversation;

import java.util.List;

public record KnowledgeContext(String query, String prompt, List<String> passages) {
    public KnowledgeContext {
        query = query == null ? "" : query;
        prompt = prompt == null ? "" : prompt;
        passages = passages == null ? List.of() : List.copyOf(passages);
    }

    public static KnowledgeContext empty(String query) {
        return new KnowledgeContext(query, "", List.of());
    }

    public boolean isEmpty() {
        return prompt.isBlank() && passages.isEmpty();
    }
}
