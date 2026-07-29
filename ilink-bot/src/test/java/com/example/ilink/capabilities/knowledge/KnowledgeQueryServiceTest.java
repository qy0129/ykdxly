package com.example.ilink.capabilities.knowledge;

import com.example.ilink.capabilities.documents.rag.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeQueryServiceTest {

    private KnowledgeQueryService queryService;
    private Retriever retriever;
    private static final String USER_ID = "test-user";

    @BeforeEach
    void setUp() {
        VectorStore vectorStore = new VectorStore(false);
        EmbeddingService embeddingService = new EmbeddingService(HttpClient.newHttpClient()) {
            @Override
            public List<Float> embed(String text) throws Exception {
                return List.of(0.1f, 0.2f, 0.3f, 0.4f);
            }
        };
        retriever = new Retriever(embeddingService, vectorStore);
        queryService = new KnowledgeQueryService(retriever);
    }

    @Test
    void returnsEmptyWhenNoKnowledge() {
        KnowledgeQueryService.KnowledgeResult result = queryService.query(USER_ID, "Spring Boot是什么？");
        assertFalse(result.found());
        assertTrue(result.references().isEmpty());
        assertTrue(result.contextPrompt().isBlank());
    }

    @Test
    void returnsEmptyForBlankInput() {
        KnowledgeQueryService.KnowledgeResult result = queryService.query(USER_ID, "");
        assertFalse(result.found());
        result = queryService.query(USER_ID, null);
        assertFalse(result.found());
        result = queryService.query(null, "test");
        assertFalse(result.found());
    }

    @Test
    void returnsKnowledgeAfterIndexing() throws Exception {
        retriever.indexDocument(USER_ID, "test.md",
                "Spring Boot是一个用于简化Spring应用开发的框架。它提供了自动配置和起步依赖。");

        KnowledgeQueryService.KnowledgeResult result = queryService.query(USER_ID, "Spring Boot是什么？");
        assertTrue(result.found());
        assertFalse(result.references().isEmpty());
        assertFalse(result.contextPrompt().isBlank());

        KnowledgeReference ref = result.references().get(0);
        assertEquals("test.md", ref.documentName());
        assertTrue(ref.score() > 0);
    }

    @Test
    void citationFormat() {
        KnowledgeReference ref = new KnowledgeReference("doc.pdf", 2, "some content", 0.95);
        assertEquals("[doc.pdf 第3段]", ref.toCitation());
        assertTrue(ref.toCitationWithScore().contains("相似度"));
    }

    @Test
    void userIsolation() throws Exception {
        retriever.indexDocument("user-a", "a-doc.md", "用户A的私有内容");
        retriever.indexDocument("user-b", "b-doc.md", "用户B的私有内容");

        KnowledgeQueryService.KnowledgeResult resultA = queryService.query("user-a", "私有内容");
        assertTrue(resultA.found());

        KnowledgeQueryService.KnowledgeResult resultB = queryService.query("user-b", "私有内容");
        assertTrue(resultB.found());

        queryService = new KnowledgeQueryService(retriever);
        KnowledgeQueryService.KnowledgeResult resultOther = queryService.query("user-c", "私有内容");
        assertFalse(resultOther.found());
    }

    @Test
    void hasKnowledgeReturnsCorrectly() throws Exception {
        assertFalse(queryService.hasKnowledge(USER_ID));
        retriever.indexDocument(USER_ID, "test.md", "一些测试知识内容");
        assertTrue(queryService.hasKnowledge(USER_ID));
    }

    @Test
    void multipleReferences() throws Exception {
        retriever.indexDocument(USER_ID, "doc1.md",
                "Spring Boot的自动配置会根据类路径中的依赖自动配置Spring应用。");
        retriever.indexDocument(USER_ID, "doc2.md",
                "Spring Boot起步依赖简化了项目依赖管理。开发者只需添加一个起步依赖。");

        KnowledgeQueryService.KnowledgeResult result = queryService.query(USER_ID, "Spring Boot自动配置");
        assertTrue(result.found());
        assertTrue(result.references().size() >= 1);
    }
}
