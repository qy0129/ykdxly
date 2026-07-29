package com.example.ilink.capabilities.documents.rag;

import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RetrieverTest {

    @Test
    void indexesSameContentOnlyOncePerUser() throws Exception {
        CountingEmbedding embedding = new CountingEmbedding();
        Retriever retriever = new Retriever(embedding, new VectorStore(false));

        Retriever.IndexResult first = retriever.indexDocument("user", "first.txt", "same content");
        Retriever.IndexResult duplicate = retriever.indexDocument("user", "renamed.txt", "same content");

        assertTrue(first.indexed());
        assertFalse(duplicate.indexed());
        assertEquals(1, embedding.calls.get());
    }

    @Test
    void retrievalFailureDegradesToEmptyContext() {
        VectorStore store = new VectorStore(false);
        store.store("user", new TextChunk("notes.txt", 0, "known text"), List.of(1f));
        EmbeddingService failing = new EmbeddingService(HttpClient.newHttpClient()) {
            @Override
            public List<Float> embed(String text) {
                throw new IllegalStateException("offline");
            }
        };

        RagContext context = new RagContextService(new Retriever(failing, store))
                .retrieve("user", "question");

        assertTrue(context.isEmpty());
    }

    private static final class CountingEmbedding extends EmbeddingService {
        private final AtomicInteger calls = new AtomicInteger();

        private CountingEmbedding() {
            super(HttpClient.newHttpClient());
        }

        @Override
        public List<Float> embed(String text) {
            calls.incrementAndGet();
            return List.of((float) text.length(), 1f);
        }
    }
}
