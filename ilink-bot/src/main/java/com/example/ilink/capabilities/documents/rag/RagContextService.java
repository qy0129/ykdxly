package com.example.ilink.capabilities.documents.rag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 为路由和聊天复用同一次检索结果，并统一处理失败降级。 */
public final class RagContextService {
    private static final int TOP_K = 3;
    private static final long CACHE_MILLIS = 60_000;

    private final Retriever retriever;
    private final Map<String, CachedContext> cache = new ConcurrentHashMap<>();

    public RagContextService(Retriever retriever) {
        this.retriever = retriever;
    }

    public Retriever.IndexResult indexDocument(String userId, String fileName, String text) throws Exception {
        Retriever.IndexResult result = retriever.indexDocument(userId, fileName, text);
        cache.keySet().removeIf(key -> key.startsWith(userId + "\u0000"));
        return result;
    }

    public RagContext retrieve(String userId, String query) {
        if (query == null || query.isBlank() || !retriever.hasKnowledge(userId)) {
            return RagContext.empty(query);
        }
        String key = userId + "\u0000" + query.strip();
        CachedContext cached = cache.get(key);
        if (cached != null && cached.expiresAtMillis() > System.currentTimeMillis()) return cached.context();
        try {
            List<VectorStore.ScoredChunk> matches = retriever.retrieve(userId, query, TOP_K);
            RagContext context = new RagContext(query, Retriever.formatContext(matches), matches.stream()
                    .map(match -> new RagPassage(match.chunk().fileName(), match.chunk().chunkIndex(),
                            match.chunk().text(), match.score())).toList());
            cache.put(key, new CachedContext(context, System.currentTimeMillis() + CACHE_MILLIS));
            return context;
        } catch (Exception error) {
            System.err.println("[RAG] 检索增强失败，继续普通处理: " + error.getMessage());
            return RagContext.empty(query);
        }
    }

    private record CachedContext(RagContext context, long expiresAtMillis) { }
}
