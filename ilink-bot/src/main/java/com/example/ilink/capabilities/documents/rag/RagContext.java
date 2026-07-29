package com.example.ilink.capabilities.documents.rag;

import java.util.List;

/** 一次消息检索得到的增强上下文。 */
public record RagContext(String query, String prompt, List<RagPassage> passages) {
    public RagContext {
        query = query == null ? "" : query;
        prompt = prompt == null ? "" : prompt;
        passages = passages == null ? List.of() : List.copyOf(passages);
    }

    public static RagContext empty(String query) {
        return new RagContext(query, "", List.of());
    }

    public boolean isEmpty() {
        return passages.isEmpty();
    }
}
