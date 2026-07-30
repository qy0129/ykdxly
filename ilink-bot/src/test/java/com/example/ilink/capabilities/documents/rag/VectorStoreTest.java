package com.example.ilink.capabilities.documents.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VectorStoreTest {

    @Test
    void returnsOnlyHighestScoringChunksInDescendingOrder() {
        VectorStore store = new VectorStore(false);
        store.store("user", new TextChunk("a.txt", 0, "first"), List.of(1f, 0f));
        store.store("user", new TextChunk("b.txt", 0, "second"), List.of(0.8f, 0.2f));
        store.store("user", new TextChunk("c.txt", 0, "third"), List.of(0f, 1f));

        List<VectorStore.ScoredChunk> results = store.search("user", List.of(1f, 0f), 2);

        assertEquals(2, results.size());
        assertEquals("a.txt", results.get(0).chunk().fileName());
        assertEquals("b.txt", results.get(1).chunk().fileName());
        assertTrue(results.get(0).score() >= results.get(1).score());
    }

    @Test
    void zeroTopKReturnsNoResults() {
        VectorStore store = new VectorStore(false);
        store.store("user", new TextChunk("a.txt", 0, "first"), List.of(1f));

        assertTrue(store.search("user", List.of(1f), 0).isEmpty());
    }

    @Test
    void keepsKnowledgeIsolatedByUser() {
        VectorStore store = new VectorStore(false);
        store.store("alice", new TextChunk("private.txt", 0, "alice only"), List.of(1f, 0f));

        assertEquals(1, store.search("alice", List.of(1f, 0f), 3).size());
        assertTrue(store.search("bob", List.of(1f, 0f), 3).isEmpty());
    }

    @Test
    void excludesLowScoreAndMismatchedDimensions() {
        VectorStore store = new VectorStore(false);
        store.store("user", new TextChunk("irrelevant.txt", 0, "irrelevant"), List.of(0f, 1f));
        store.store("user", new TextChunk("old-model.txt", 0, "old"), List.of(1f));

        assertTrue(store.search("user", List.of(1f, 0f), 3, 0.55).isEmpty());
    }
}
