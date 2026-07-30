package com.example.ilink.capabilities.documents.rag;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 为路由和聊天复用同一次检索结果，并统一处理失败降级。 */
public final class RagContextService {
    private static final int TOP_K = 3;
    private static final long CACHE_MILLIS = 60_000;
    private static final int MAX_CACHE_ENTRIES = 1_000;

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
            if (matches.isEmpty()) return RagContext.empty(query);
            RagContext context = new RagContext(query, Retriever.formatContext(matches), matches.stream()
                    .map(match -> new RagPassage(match.chunk().fileName(), match.chunk().chunkIndex(),
                            match.chunk().text(), match.score())).toList());
            cache.put(key, new CachedContext(context, System.currentTimeMillis() + CACHE_MILLIS));
            trimCache();
            return context;
        } catch (Exception error) {
            System.err.println("[RAG] 检索增强失败，继续普通处理: " + error.getMessage());
            return RagContext.empty(query);
        }
    }

    private void trimCache() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        if (cache.size() <= MAX_CACHE_ENTRIES) return;
        cache.keySet().stream().limit(cache.size() - MAX_CACHE_ENTRIES).toList().forEach(cache::remove);
    }

    private record CachedContext(RagContext context, long expiresAtMillis) { }
}
