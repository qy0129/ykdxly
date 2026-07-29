package com.example.ilink.application.conversation;

import com.example.ilink.capabilities.documents.rag.RagContext;
import com.example.ilink.capabilities.documents.rag.RagContextService;

public final class KnowledgeContextProvider {
    private final RagContextService ragContextService;

    public KnowledgeContextProvider(RagContextService ragContextService) {
        this.ragContextService = ragContextService;
    }

    public KnowledgeContext build(String userId, String query) {
        RagContext rag = ragContextService.retrieve(userId, query);
        return new KnowledgeContext(rag.query(), rag.prompt(),
                rag.passages().stream()
                        .map(p -> p.fileName() + "[" + p.chunkIndex() + "]: " + p.text())
                        .toList());
    }
}
