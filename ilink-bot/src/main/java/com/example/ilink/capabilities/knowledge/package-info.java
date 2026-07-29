/**
 * Knowledge Module — 个人知识库。
 *
 * <p>支持文件上传解析、文档切片、Embedding 向量化、RAG 检索和引用回答。
 * 用户隔离按 userId 保证，每个用户只能检索自己的知识库。</p>
 *
 * <p>核心流程：</p>
 * <pre>
 * 文件上传 → DocumentParser → ChunkSplitter → EmbeddingService → VectorStore
 * 用户提问 → KnowledgeQueryService → Retriever → LLM + 引用来源 → 回答
 * </pre>
 */
package com.example.ilink.capabilities.knowledge;
